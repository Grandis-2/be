package com.grandis.nova.preorder.outbox.publish;

import com.grandis.nova.preorder.outbox.OutboxAppended;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/** 커밋 후 콜백은 어떤 경우에도 요청 쪽으로 예외를 올리지 않는다. 못 보낸 행은 릴레이 몫이다. */
class OutboxAfterCommitPublisherTest {

    final OutboxPublisher publisher = mock(OutboxPublisher.class);

    @Test
    void 발행_중_DB_오류가_나도_삼키고_로그만_남긴다() {
        willThrow(new DataAccessResourceFailureException("db down")).given(publisher).publishById(1L);
        OutboxAfterCommitPublisher listener = new OutboxAfterCommitPublisher(publisher, new SyncTaskExecutor());

        assertThatCode(() -> listener.onAppended(new OutboxAppended(1L))).doesNotThrowAnyException();
        verify(publisher).publishById(1L);
    }

    @Test
    void 실행기가_가득_차면_기다리지_않고_거절해_릴레이에_맡긴다() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        willAnswer(invocation -> release.await(5, TimeUnit.SECONDS)).given(publisher).publishById(1L);
        try (SimpleAsyncTaskExecutor executor = new OutboxConfig()
                .outboxPublishExecutor(new OutboxProperties(1, Duration.ofMinutes(1), 100, Duration.ofMinutes(10)))) {
            OutboxAfterCommitPublisher listener = new OutboxAfterCommitPublisher(publisher, executor);
            listener.onAppended(new OutboxAppended(1L));
            verify(publisher, timeout(1_000)).publishById(1L);

            assertThatCode(() -> listener.onAppended(new OutboxAppended(2L))).doesNotThrowAnyException();

            release.countDown();
        }
        verify(publisher, never()).publishById(2L);
    }
}
