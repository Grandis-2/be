package com.grandis.nova.common.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 커밋 직후 발행 실행기에 넘기고 바로 돌아간다. 롤백된 메시지는 오지 않는다.
 * {@code @Async} 대신 직접 넘겨 거절 예외가 요청까지 올라가지 않게 한다. 못 보낸 행은 릴레이가 보낸다.
 *
 * 실행기는 빈으로 두지 않는다 — Executor 빈이 하나라도 있으면 Boot 가 기본 applicationTaskExecutor 를 만들지 않는다.
 */
class OutboxAfterCommitPublisher implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(OutboxAfterCommitPublisher.class);
    /** 종료 시 진행 중인 발행을 기다리는 시간. */
    private static final long TERMINATION_TIMEOUT_MILLIS = 5_000;

    private final OutboxPublisher publisher;
    private final TaskExecutor executor;

    OutboxAfterCommitPublisher(OutboxPublisher publisher, TaskExecutor executor) {
        this.publisher = publisher;
        this.executor = executor;
    }

    /**
     * 커밋 직후 발행 실행기. 건마다 가상 스레드를 쓰고 동시 실행 수로 상한을 둔다.
     * 넘치면 기다리지 않고 거절해 요청 스레드를 붙잡지 않는다(릴레이가 보낸다).
     */
    static SimpleAsyncTaskExecutor executor(int concurrency) {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("outbox-publish-");
        executor.setVirtualThreads(true);
        executor.setConcurrencyLimit(concurrency);
        executor.setRejectTasksWhenLimitReached(true);
        executor.setTaskTerminationTimeout(TERMINATION_TIMEOUT_MILLIS);
        return executor;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onAppended(OutboxAppended appended) {
        try {
            executor.execute(() -> publishQuietly(appended.outboxEventId()));
        } catch (TaskRejectedException e) {
            log.warn("발행 실행기가 가득 차 릴레이에 맡긴다 outboxEventId={}", appended.outboxEventId());
        }
    }

    @Override
    public void destroy() throws Exception {
        if (executor instanceof AutoCloseable closeable) {
            closeable.close();
        }
    }

    /** 발행 스레드에서 새는 예외를 로그로 남긴다. 행은 미발행으로 남는다. */
    private void publishQuietly(Long outboxEventId) {
        try {
            publisher.publishById(outboxEventId);
        } catch (RuntimeException e) {
            log.warn("커밋 직후 발행 중 오류 — 릴레이에 맡긴다 outboxEventId={}", outboxEventId, e);
        }
    }
}
