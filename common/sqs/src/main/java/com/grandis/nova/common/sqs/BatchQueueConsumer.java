package com.grandis.nova.common.sqs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import software.amazon.awssdk.retries.api.BackoffStrategy;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchRequestEntry;
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchResponse;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;

/**
 * 호출자가 알려 주는 빈 자리 수만큼만(한 번에 최대 10) 받아 메시지마다 동시에 처리한다. 빈 자리가 없으면 받지 않는다.
 * 받은 묶음이 끝나기를 기다리지 않고 다시 받으므로, 빈 자리 수는 {@link #inFlight()} 를 빼고 알려 준다.
 * 끝내는 방식은 처리 함수가 {@link MessageOutcome} 으로 정하고, 지울 메시지는 모아서 묶음으로 지운다.
 */
public final class BatchQueueConsumer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(BatchQueueConsumer.class);
    /** SQS 가 한 번에 받고 지우는 최대 건수. */
    private static final int MAX_BATCH = 10;
    private static final BackoffStrategy RECEIVE_FAILURE_BACKOFF =
            BackoffStrategy.exponentialDelayHalfJitter(Duration.ofSeconds(1), Duration.ofSeconds(30));
    private static final Duration RECEIVE_TIMEOUT_MARGIN = Duration.ofSeconds(5);
    /** 지울 메시지가 하나 생긴 뒤 묶음을 채우려고 더 기다리는 시간. */
    private static final Duration DELETE_LINGER = Duration.ofMillis(100);
    /** 삭제 작업을 끝내라는 표식. 종료 때 남은 삭제 뒤에 넣는다. */
    private static final Message STOP = Message.builder().build();

    private final SqsClient sqs;
    private final SqsQueueUrls queueUrls;
    private final BatchConsumerSettings settings;
    private final List<String> messageAttributeNames;
    private final IntSupplier freeSlots;
    private final BatchMessageHandler handler;
    private final Duration receiveTimeout;
    private final AtomicInteger inFlight = new AtomicInteger();
    private final BlockingQueue<Message> deletions = new LinkedBlockingQueue<>();
    private ExecutorService handlers;
    private Thread receiver;
    private Thread deleter;
    private volatile boolean running;
    private volatile boolean paused;

    /**
     * @param messageAttributeNames 함께 받을 메시지 속성 이름. 적지 않은 속성은 받은 메시지에 없다
     * @param freeSlots             지금 더 받아도 되는 건수. 0 이하면 받지 않는다. 받기 스레드 하나가 받기 직전마다 묻는다
     */
    public BatchQueueConsumer(SqsClient sqs, SqsQueueUrls queueUrls, BatchConsumerSettings settings,
                              List<String> messageAttributeNames, IntSupplier freeSlots, BatchMessageHandler handler) {
        this.sqs = sqs;
        this.queueUrls = queueUrls;
        this.settings = settings;
        this.messageAttributeNames = List.copyOf(messageAttributeNames);
        this.freeSlots = freeSlots;
        this.handler = handler;
        this.receiveTimeout = Duration.ofSeconds(settings.waitSeconds()).plus(RECEIVE_TIMEOUT_MARGIN);
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        deletions.clear();
        handlers = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name(settings.queue() + "-handler-", 0).factory());
        deleter = Thread.ofVirtual().name(settings.queue() + "-deleter").start(() -> guarded(this::deleteLoop));
        receiver = Thread.ofVirtual().name(settings.queue() + "-receiver").start(() -> guarded(this::receiveLoop));
    }

    /**
     * 새로 받지 않고(진행 중인 롱 폴링이 돌려준 메시지는 처리 함수에 넘긴다) 처리 중인 메시지를 stopTimeout 까지 기다린 뒤
     * 남은 삭제를 마친다. 시간 안에 끝나지 않은 처리는 인터럽트한다 — 그 메시지는 가시성 시간 뒤 다시 받는다.
     */
    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        try {
            if (!receiver.join(Duration.ofSeconds(settings.waitSeconds() + 10L))) {
                log.warn("{} 받기가 종료 시간 안에 끝나지 않아 인터럽트한다", settings.queue());
                receiver.interrupt();
            }
            handlers.shutdown();
            if (!handlers.awaitTermination(settings.stopTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("{} 처리 중인 메시지가 종료 시간 안에 끝나지 않아 인터럽트한다 inFlight={}",
                        settings.queue(), inFlight.get());
                handlers.shutdownNow();
            }
            deletions.add(STOP);
            if (!deleter.join(settings.stopTimeout())) {
                log.warn("{} 남은 삭제가 종료 시간 안에 끝나지 않아 인터럽트한다", settings.queue());
                deleter.interrupt();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 멈추지 않았고 받기 · 삭제 작업이 모두 살아 있다. */
    @Override
    public boolean isRunning() {
        Thread receiving = receiver;
        Thread deleting = deleter;
        return running && receiving != null && receiving.isAlive() && deleting != null && deleting.isAlive();
    }

    /** 새로 받지 않는다. 진행 중인 롱 폴링이 돌려주는 메시지는 처리 함수에 넘어간다. 처리 중인 메시지는 그대로 끝낸다. */
    public void pause() {
        paused = true;
    }

    public void resume() {
        paused = false;
    }

    public boolean isPaused() {
        return paused;
    }

    /** 받았지만 아직 처리 함수가 끝나지 않은 건수. */
    public int inFlight() {
        return inFlight.get();
    }

    private void guarded(Runnable loop) {
        try {
            loop.run();
        } catch (Throwable e) {
            log.error("{} 큐 소비 작업이 예상 못 한 오류로 끝났다 — 다시 띄우지 않는다 worker={}",
                    settings.queue(), Thread.currentThread().getName(), e);
        }
    }

    private void receiveLoop() {
        int failures = 0;
        while (running) {
            try {
                int slots = paused ? 0 : Math.min(freeSlots.getAsInt(), MAX_BATCH);
                if (slots <= 0) {
                    if (!sleep(settings.idleWait())) {
                        return;
                    }
                    continue;
                }
                String queueUrl = queueUrls.of(settings.queue());
                List<Message> messages = sqs.receiveMessage(request -> request
                        .queueUrl(queueUrl)
                        .waitTimeSeconds(settings.waitSeconds())
                        .maxNumberOfMessages(slots)
                        .visibilityTimeout((int) settings.visibility().toSeconds())
                        .messageSystemAttributeNames(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT,
                                MessageSystemAttributeName.SENT_TIMESTAMP)
                        .messageAttributeNames(messageAttributeNames)
                        .overrideConfiguration(config -> config.apiCallTimeout(receiveTimeout)))
                        .messages();
                failures = 0;
                messages.forEach(message -> dispatch(queueUrl, message));
            } catch (RuntimeException e) {
                failures++;
                Duration pause = RECEIVE_FAILURE_BACKOFF.computeDelay(failures + 1);
                log.warn("{} 큐를 받지 못했다 — {} 뒤 다시 받는다 failures={}", settings.queue(), pause, failures, e);
                if (!sleep(pause)) {
                    return;
                }
            }
        }
    }

    private void dispatch(String queueUrl, Message message) {
        inFlight.incrementAndGet();
        try {
            handlers.execute(() -> process(queueUrl, message));
        } catch (RejectedExecutionException e) {
            inFlight.decrementAndGet();
        }
    }

    /** 한 건이 낸 예외 · Error 는 그 메시지에서 끝낸다(가시성 시간 뒤 다시 보인다). 메모리 부족만 다시 던진다. */
    private void process(String queueUrl, Message message) {
        try {
            MessageOutcome outcome = handler.handle(message);
            switch (outcome) {
                case MessageOutcome.Delete _ -> deletions.add(message);
                case MessageOutcome.Return(Duration delay) -> returnAfter(queueUrl, message, delay);
                case MessageOutcome.Keep _ -> {
                }
            }
        } catch (OutOfMemoryError e) {
            throw e;
        } catch (Exception | Error e) {
            log.warn("{} 메시지를 끝내지 못했다 — 가시성 시간 뒤 다시 보인다 messageId={}",
                    settings.queue(), message.messageId(), e);
        } finally {
            inFlight.decrementAndGet();
        }
    }

    private void returnAfter(String queueUrl, Message message, Duration delay) {
        try {
            sqs.changeMessageVisibility(request -> request.queueUrl(queueUrl)
                    .receiptHandle(message.receiptHandle())
                    .visibilityTimeout((int) ((delay.toMillis() + 999) / 1000)));
        } catch (RuntimeException e) {
            log.warn("다시 보일 시각을 바꾸지 못했다 — 가시성 시간이 지나면 다시 보인다 queue={} messageId={}",
                    settings.queue(), message.messageId(), e);
        }
    }

    private void deleteLoop() {
        List<Message> batch = new ArrayList<>(MAX_BATCH);
        try {
            while (true) {
                Message first = deletions.take();
                if (first == STOP) {
                    return;
                }
                batch.add(first);
                boolean stopping = false;
                long deadline = System.nanoTime() + DELETE_LINGER.toNanos();
                while (batch.size() < MAX_BATCH) {
                    Message next = deletions.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
                    if (next == null) {
                        break;
                    }
                    if (next == STOP) {
                        stopping = true;
                        break;
                    }
                    batch.add(next);
                }
                deleteBatch(batch);
                batch.clear();
                if (stopping) {
                    return;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void deleteBatch(List<Message> batch) {
        List<DeleteMessageBatchRequestEntry> entries = new ArrayList<>(batch.size());
        for (int i = 0; i < batch.size(); i++) {
            entries.add(DeleteMessageBatchRequestEntry.builder()
                    .id(Integer.toString(i)).receiptHandle(batch.get(i).receiptHandle()).build());
        }
        try {
            DeleteMessageBatchResponse response = sqs.deleteMessageBatch(request -> request
                    .queueUrl(queueUrls.of(settings.queue())).entries(entries));
            response.failed().forEach(failed -> log.warn(
                    "처리한 메시지를 지우지 못했다 — 다시 받으면 멱등하게 한 번 더 처리된다 queue={} messageId={} code={} reason={}",
                    settings.queue(), batch.get(Integer.parseInt(failed.id())).messageId(), failed.code(),
                    failed.message()));
        } catch (RuntimeException e) {
            log.warn("처리한 메시지 {}건을 지우지 못했다 — 다시 받으면 멱등하게 한 번 더 처리된다 queue={}",
                    batch.size(), settings.queue(), e);
        }
    }

    /** @return 인터럽트되면 false — 이 작업 스레드를 끝낸다 */
    private static boolean sleep(Duration duration) {
        try {
            Thread.sleep(duration);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
