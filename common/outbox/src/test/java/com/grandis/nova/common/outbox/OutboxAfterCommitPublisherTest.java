package com.grandis.nova.common.outbox;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/** 커밋 후 콜백은 어떤 경우에도 요청 쪽으로 예외를 올리지 않고 기다리지 않는다. 못 보낸 행은 릴레이 몫이다. */
class OutboxAfterCommitPublisherTest {

    final OutboxPublisher publisher = mock(OutboxPublisher.class);
    final CountDownLatch release = new CountDownLatch(1);
    OutboxAfterCommitPublisher listener;

    @AfterEach
    void tearDown() throws Exception {
        release.countDown();
        if (listener != null) {
            listener.destroy();
        }
    }

    @Test
    void 발행_중_DB_오류를_삼키고_다음_행을_보낸다() {
        given(publisher.batchSize()).willReturn(1);
        willThrow(new DataAccessResourceFailureException("db down")).given(publisher).publishAll(List.of(row(1)), null);
        listener = started(1, 10);

        assertThatCode(() -> listener.onAppended(new OutboxAppended(row(1)))).doesNotThrowAnyException();
        listener.onAppended(new OutboxAppended(row(2)));

        verify(publisher, timeout(1_000)).publishAll(List.of(row(2)), null);
    }

    @Test
    void 버퍼가_가득_차면_기다리지_않고_넘긴다() {
        given(publisher.batchSize()).willReturn(1);
        blockWhilePublishing();
        listener = started(1, 1);
        listener.onAppended(new OutboxAppended(row(1)));
        verify(publisher, timeout(1_000)).publishAll(List.of(row(1)), null);
        listener.onAppended(new OutboxAppended(row(2)));

        assertThatCode(() -> listener.onAppended(new OutboxAppended(row(3)))).doesNotThrowAnyException();

        release.countDown();
        verify(publisher, timeout(1_000)).publishAll(List.of(row(2)), null);
        verify(publisher, after(300).never()).publishAll(List.of(row(3)), null);
    }

    @Test
    void 쌓인_행은_묶음_크기까지_한_번에_꺼낸다() {
        given(publisher.batchSize()).willReturn(10);
        blockWhilePublishing();
        listener = started(1, 100);
        listener.onAppended(new OutboxAppended(row(1)));
        verify(publisher, timeout(1_000)).publishAll(List.of(row(1)), null);
        for (int i = 2; i <= 13; i++) {
            listener.onAppended(new OutboxAppended(row(i)));
        }

        release.countDown();

        verify(publisher, timeout(1_000)).publishAll(List.of(row(2), row(3), row(4), row(5), row(6), row(7), row(8),
                row(9), row(10), row(11)), null);
        verify(publisher, timeout(1_000)).publishAll(List.of(row(12), row(13)), null);
    }

    @Test
    void 멈춘_뒤에_들어온_행은_보내지_않는다() throws Exception {
        given(publisher.batchSize()).willReturn(1);
        listener = started(2, 10);
        listener.destroy();

        listener.onAppended(new OutboxAppended(row(1)));

        verify(publisher, after(300).never()).publishAll(anyList(), any());
        listener = null;
    }

    private OutboxAfterCommitPublisher started(int concurrency, int buffer) {
        OutboxAfterCommitPublisher started = new OutboxAfterCommitPublisher(publisher, concurrency, buffer);
        started.afterPropertiesSet();
        return started;
    }

    /** 첫 발행이 release 까지 멈춘다. */
    private void blockWhilePublishing() {
        willAnswer(invocation -> release.await(5, TimeUnit.SECONDS)).given(publisher).publishAll(List.of(row(1)), null);
    }

    private static OutboxRow row(long id) {
        return new OutboxRow(id, "event-" + id, "ITEM", new UUID(0, id), "ITEM_SETTLED", "{}", 0,
                Instant.parse("2026-10-21T00:00:00Z"), null);
    }
}
