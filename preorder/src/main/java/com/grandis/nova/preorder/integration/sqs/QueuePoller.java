package com.grandis.nova.preorder.integration.sqs;

import com.grandis.nova.preorder.deadletter.DeadLetterRedriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import software.amazon.awssdk.retries.api.BackoffStrategy;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 큐 하나를 롱 폴링으로 받아 메시지마다 {@link #handle} 에 넘긴다. 받기가 실패하면 쉬었다 다시 받는다.
 * 종료 때는 새로 받지 않고, 받은 묶음을 마저 처리한 뒤 멈춘다.
 */
abstract class QueuePoller implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(QueuePoller.class);
    /** 받기가 연달아 실패하면(큐 장애) 1초에서 두 배씩 30초까지 쉰다 — 장애 동안 로그 · 요청이 쏟아지지 않게. */
    private static final BackoffStrategy RECEIVE_FAILURE_BACKOFF =
            BackoffStrategy.exponentialDelayHalfJitter(Duration.ofSeconds(1), Duration.ofSeconds(30));
    /** 롱 폴링은 대기 시간만큼 걸리므로 클라이언트 기본 제한 시간 대신 대기 시간에 여유를 더해 쓴다. */
    private static final Duration RECEIVE_TIMEOUT_MARGIN = Duration.ofSeconds(5);

    protected final SqsClient sqs;
    private final SqsQueueUrls queueUrls;
    private final String queue;
    private final int concurrency;
    private final int waitSeconds;
    private final int maxMessages;
    private final Duration visibility;
    private final Duration receiveTimeout;
    private final List<Thread> workers = new ArrayList<>();
    private volatile boolean running;

    QueuePoller(SqsClient sqs, SqsQueueUrls queueUrls, String queue, int concurrency, int waitSeconds,
                int maxMessages, Duration visibility) {
        this.sqs = sqs;
        this.queueUrls = queueUrls;
        this.queue = queue;
        this.concurrency = concurrency;
        this.waitSeconds = waitSeconds;
        this.maxMessages = maxMessages;
        this.visibility = visibility;
        this.receiveTimeout = Duration.ofSeconds(waitSeconds).plus(RECEIVE_TIMEOUT_MARGIN);
    }

    /** 메시지 하나를 끝낸다. 한 건의 실패가 같은 묶음의 나머지를 막지 않게 예외를 밖으로 내지 않는다. */
    protected abstract void handle(String queueUrl, Message message);

    @Override
    public void start() {
        running = true;
        for (int i = 0; i < concurrency; i++) {
            workers.add(Thread.ofVirtual().name(queue + "-" + i).start(this::poll));
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
                if (!worker.join(Duration.ofSeconds(waitSeconds + 10L))) {
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

    @Override
    public boolean isRunning() {
        return running;
    }

    /** 되돌린 메시지에 실린 DLQ 행 id. 없거나 읽을 수 없으면 비어 있다. */
    protected static Optional<Long> deadLetterId(Message message) {
        return Optional.ofNullable(message.messageAttributes().get(DeadLetterRedriver.DEAD_LETTER_ID_ATTRIBUTE))
                .map(MessageAttributeValue::stringValue)
                .flatMap(value -> {
                    try {
                        return Optional.of(Long.parseLong(value));
                    } catch (NumberFormatException e) {
                        return Optional.empty();
                    }
                });
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

    private void poll() {
        int failures = 0;
        while (running) {
            try {
                String queueUrl = queueUrls.of(queue);
                List<Message> messages = sqs.receiveMessage(request -> request
                        .queueUrl(queueUrl)
                        .waitTimeSeconds(waitSeconds)
                        .maxNumberOfMessages(maxMessages)
                        .visibilityTimeout((int) visibility.toSeconds())
                        .messageSystemAttributeNames(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT,
                                MessageSystemAttributeName.SENT_TIMESTAMP)
                        .messageAttributeNames(DeadLetterRedriver.DEAD_LETTER_ID_ATTRIBUTE)
                        .overrideConfiguration(config -> config.apiCallTimeout(receiveTimeout)))
                        .messages();
                failures = 0;
                messages.forEach(message -> handle(queueUrl, message));
            } catch (RuntimeException e) {
                failures++;
                Duration pause = RECEIVE_FAILURE_BACKOFF.computeDelay(failures + 1);
                log.warn("{} 큐를 받지 못했다 — {} 뒤 다시 받는다 failures={}", queue, pause, failures, e);
                if (!pause(pause)) {
                    return;
                }
            }
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
