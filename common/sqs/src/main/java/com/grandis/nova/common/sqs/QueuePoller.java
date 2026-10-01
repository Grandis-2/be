package com.grandis.nova.common.sqs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import software.amazon.awssdk.retries.api.BackoffStrategy;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 큐 하나를 롱 폴링으로 받아 메시지마다 {@link #handle} 에 넘긴다. 받기가 실패하면 쉬었다 다시 받는다.
 * 종료 때는 새로 받지 않고, 받은 묶음을 마저 처리한 뒤 멈춘다. 끝내는 방식(지우기 · 다시 보이기 · DB 적재)은 하위 클래스가 정한다.
 *
 * 작업 스레드가 예상 못 한 오류(메모리 부족)로 끝나면 ERROR 로 남기고, 모든 작업이 끝나면 {@link #isRunning()} 이 false 가 된다.
 * 죽은 작업은 다시 띄우지 않는다 — 메모리 부족이면 반복될 뿐이다.
 */
public abstract class QueuePoller implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(QueuePoller.class);
    /** 받기가 연달아 실패하면(큐 장애) 1초에서 두 배씩 30초까지 쉰다 — 장애 동안 로그 · 요청이 쏟아지지 않게. */
    private static final BackoffStrategy RECEIVE_FAILURE_BACKOFF =
            BackoffStrategy.exponentialDelayHalfJitter(Duration.ofSeconds(1), Duration.ofSeconds(30));
    /** 롱 폴링은 대기 시간만큼 걸리므로 클라이언트 기본 제한 시간 대신 대기 시간에 여유를 더해 쓴다. */
    private static final Duration RECEIVE_TIMEOUT_MARGIN = Duration.ofSeconds(5);

    protected final SqsClient sqs;
    protected final QueuePollerSettings settings;
    private final SqsQueueUrls queueUrls;
    private final List<String> messageAttributeNames;
    private final Duration receiveTimeout;
    private final List<Thread> workers = new ArrayList<>();
    private final AtomicInteger liveWorkers = new AtomicInteger();
    private volatile boolean running;

    /**
     * @param messageAttributeNames 함께 받을 메시지 속성 이름. 보내는 쪽이 실은 속성(DLQ 되돌리기 표식 등)을 읽으려면 적는다 —
     *                              적지 않은 속성은 받은 메시지에 없다
     */
    protected QueuePoller(SqsClient sqs, SqsQueueUrls queueUrls, QueuePollerSettings settings,
                          List<String> messageAttributeNames) {
        this.sqs = sqs;
        this.queueUrls = queueUrls;
        this.settings = settings;
        this.messageAttributeNames = List.copyOf(messageAttributeNames);
        this.receiveTimeout = Duration.ofSeconds(settings.waitSeconds()).plus(RECEIVE_TIMEOUT_MARGIN);
    }

    /** 메시지 하나를 끝낸다(지우기 · 다시 보이기 · 적재 등). 새는 예외 · Error 는 남기고 같은 묶음의 다음 메시지로 넘어간다. */
    protected abstract void handle(String queueUrl, Message message);

    @Override
    public void start() {
        running = true;
        for (int i = 0; i < settings.concurrency(); i++) {
            liveWorkers.incrementAndGet();
            workers.add(Thread.ofVirtual().name(settings.queue() + "-" + i).start(this::work));
        }
    }

    /**
     * 받는 중인 롱 폴링(최대 waitSeconds)과 받은 묶음의 처리가 끝나기를 기다린다. 기본 단계라 DB · SQS 클라이언트보다
     * 먼저 멈춘다. 시간 안에 끝나지 않으면 남기고 인터럽트한다 — 못 지운 메시지는 가시성 시간 뒤 다시 받는다.
     */
    @Override
    public void stop() {
        running = false;
        for (Thread worker : workers) {
            try {
                if (!worker.join(Duration.ofSeconds(settings.waitSeconds() + 10L))) {
                    log.warn("큐 소비기가 종료 시간 안에 끝나지 않아 인터럽트한다 worker={}", worker.getName());
                    worker.interrupt();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        workers.clear();
    }

    /** 멈추지 않았고 받는 작업이 하나라도 살아 있다. */
    @Override
    public boolean isRunning() {
        return running && liveWorkers.get() > 0;
    }

    /** 받은 횟수. int 를 넘으면 상한으로, 읽을 수 없으면 1 로 본다 — 흐름을 끊지 않는다. */
    protected static int receiveCount(Message message) {
        try {
            long count = Long.parseLong(
                    message.attributes().getOrDefault(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT, "1"));
            return (int) Math.clamp(count, 1, Integer.MAX_VALUE - 1);
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private void work() {
        try {
            poll();
        } catch (Throwable e) {
            log.error("{} 큐 소비 작업이 예상 못 한 오류로 끝났다 — 다시 띄우지 않는다 worker={}",
                    settings.queue(), Thread.currentThread().getName(), e);
        } finally {
            liveWorkers.decrementAndGet();
        }
    }

    private void poll() {
        int failures = 0;
        while (running) {
            try {
                String queueUrl = queueUrls.of(settings.queue());
                List<Message> messages = sqs.receiveMessage(request -> request
                        .queueUrl(queueUrl)
                        .waitTimeSeconds(settings.waitSeconds())
                        .maxNumberOfMessages(settings.maxMessages())
                        .visibilityTimeout((int) settings.visibility().toSeconds())
                        .messageSystemAttributeNames(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT,
                                MessageSystemAttributeName.SENT_TIMESTAMP)
                        .messageAttributeNames(messageAttributeNames)
                        .overrideConfiguration(config -> config.apiCallTimeout(receiveTimeout)))
                        .messages();
                failures = 0;
                messages.forEach(message -> handleIsolated(queueUrl, message));
            } catch (RuntimeException e) {
                failures++;
                Duration pause = RECEIVE_FAILURE_BACKOFF.computeDelay(failures + 1);
                log.warn("{} 큐를 받지 못했다 — {} 뒤 다시 받는다 failures={}", settings.queue(), pause, failures, e);
                if (!pause(pause)) {
                    return;
                }
            }
        }
    }

    /**
     * 한 건이 낸 예외 · Error(스택 넘침 등)가 같은 묶음의 나머지를 막거나, 받기 실패로 잘못 세어지거나, 작업 스레드를 끝내지 않게
     * 가둔다. 그 메시지는 가시성 시간 뒤 다시 보인다. 메모리 부족만 다시 던진다(이어 처리해도 또 실패한다).
     */
    private void handleIsolated(String queueUrl, Message message) {
        try {
            handle(queueUrl, message);
        } catch (OutOfMemoryError e) {
            throw e;
        } catch (Exception | Error e) {
            log.warn("{} 메시지를 끝내지 못했다 — 가시성 시간 뒤 다시 보인다 messageId={}", settings.queue(), message.messageId(), e);
        }
    }

    /** @return 인터럽트되면 false — 그 뒤의 대기 · 호출이 모두 곧바로 실패하므로 이 작업 스레드를 끝낸다 */
    private static boolean pause(Duration duration) {
        try {
            Thread.sleep(duration);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
