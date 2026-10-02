package com.grandis.nova.preorder.outbox.publish;

import com.grandis.nova.preorder.outbox.OutboxAppended;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 커밋 직후 발행 실행기에 넘기고 바로 돌아간다. 롤백된 메시지는 오지 않는다.
 * {@code @Async} 대신 직접 넘겨 거절 예외가 요청까지 올라가지 않게 한다. 못 보낸 행은 릴레이가 보낸다.
 */
@Component
public class OutboxAfterCommitPublisher {

    static final String EXECUTOR = "outboxPublishExecutor";

    private static final Logger log = LoggerFactory.getLogger(OutboxAfterCommitPublisher.class);

    private final OutboxPublisher publisher;
    private final TaskExecutor executor;

    OutboxAfterCommitPublisher(OutboxPublisher publisher, @Qualifier(EXECUTOR) TaskExecutor executor) {
        this.publisher = publisher;
        this.executor = executor;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAppended(OutboxAppended appended) {
        try {
            executor.execute(() -> publishQuietly(appended.outboxEventId()));
        } catch (TaskRejectedException e) {
            log.warn("발행 실행기가 가득 차 릴레이에 맡긴다 outboxEventId={}", appended.outboxEventId());
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
