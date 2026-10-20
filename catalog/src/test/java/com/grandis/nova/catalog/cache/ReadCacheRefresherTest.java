package com.grandis.nova.catalog.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("읽기 캐시 갱신 실행기 — 크기를 묶는다")
class ReadCacheRefresherTest {

    @Test
    @DisplayName("동시 2개가 돌고 64개가 기다리면 그다음은 거절한다(캐시는 맡기지 않고 지나간다)")
    void rejectsBeyondThreadsAndQueue() throws Exception {
        ReadCacheRefresher refresher = new ReadCacheRefresher();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch running = new CountDownLatch(ReadCacheRefresher.THREADS);
        try {
            for (int i = 0; i < ReadCacheRefresher.THREADS; i++) {
                refresher.execute(() -> {
                    running.countDown();
                    await(release);
                });
            }
            assertThat(running.await(5, TimeUnit.SECONDS)).as("두 갱신이 돌고 있다").isTrue();
            for (int i = 0; i < ReadCacheRefresher.QUEUE; i++) {
                refresher.execute(() -> { });
            }
            assertThatThrownBy(() -> refresher.execute(() -> { })).isInstanceOf(RejectedExecutionException.class);
        } finally {
            release.countDown();
            refresher.shutdown();
        }
    }

    @Test
    @DisplayName("내려간 뒤에는 거절한다")
    void rejectsAfterShutdown() {
        ReadCacheRefresher refresher = new ReadCacheRefresher();
        refresher.shutdown();
        assertThatThrownBy(() -> refresher.execute(() -> { })).isInstanceOf(RejectedExecutionException.class);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
