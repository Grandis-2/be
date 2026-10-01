package com.grandis.nova.waitingroom.domain.admission;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EnqueueLatchTest {

    @Test
    void 덮을_기간을_올림하고_한_초를_더해_반드시_덮는다() {
        EnqueueLatch latch = EnqueueLatch.covering(10, Duration.ofMillis(4_500));
        latch.mark("p1", 100);

        assertThat(latch.latched("p1", 105)).as("4.5초를 덮으려면 6초").isTrue();
        assertThat(latch.latched("p1", 106)).isFalse();
    }

    @Test
    void 다시_찍어도_시각을_고치지_않는다_고치면_유입이_이어지는_동안_안_풀린다() {
        EnqueueLatch latch = EnqueueLatch.covering(10, Duration.ofSeconds(5));
        latch.mark("p1", 100);
        latch.mark("p1", 104);

        assertThat(latch.latched("p1", 106)).isFalse();
    }

    @Test
    void 만료된_표식은_지워지기_전이라도_다시_찍으면_새_시각으로_걸린다() {
        EnqueueLatch latch = EnqueueLatch.covering(10, Duration.ofSeconds(5));
        latch.mark("p1", 100);
        latch.mark("p1", 200);

        assertThat(latch.latched("p1", 203)).isTrue();
    }

    @Test
    void 시계가_뒤로_가도_수명만큼은_걸린_것으로_본다() {
        EnqueueLatch latch = EnqueueLatch.covering(10, Duration.ofSeconds(5));
        latch.mark("p1", 100);

        assertThat(latch.latched("p1", 97)).isTrue();
        assertThat(latch.latched("p1", 94)).isFalse();
    }

    @Test
    void 만료된_것은_지우고_상한을_넘으면_통째로_비운다() {
        EnqueueLatch latch = EnqueueLatch.covering(2, Duration.ofSeconds(5));
        latch.mark("p1", 100);
        latch.latched("p1", 200);
        assertThat(latch.size()).isZero();

        latch.mark("a", 100);
        latch.mark("b", 100);
        latch.mark("c", 100);
        assertThat(latch.size()).isEqualTo(1);
        assertThat(latch.latched("c", 100)).isTrue();
    }

    @Nested
    class 동시_호출 {

        static final int THREADS = 32;

        @Test
        void 같은_모델을_동시에_찍어도_걸리고_뒤에_찍은_것이_수명을_늘리지_않는다() throws Exception {
            EnqueueLatch latch = EnqueueLatch.covering(10, Duration.ofSeconds(5));

            concurrently(i -> latch.mark("p1", 100 + i % 3));

            assertThat(latch.latched("p1", 102)).isTrue();
            assertThat(latch.latched("p1", 108)).as("처음 찍힌 시각(100~102) + 수명 6초면 풀린다").isFalse();
        }

        @Test
        void 상한_안에서는_동시에_찍은_모든_모델이_걸리고_조회와_섞여도_풀리지_않는다() throws Exception {
            EnqueueLatch latch = EnqueueLatch.covering(THREADS, Duration.ofSeconds(5));

            concurrently(i -> {
                latch.mark("p" + i, 100);
                latch.latched("p" + ((i + 1) % THREADS), 101);
            });

            for (int i = 0; i < THREADS; i++) {
                assertThat(latch.latched("p" + i, 101)).as("p" + i).isTrue();
            }
        }

        /** 넘치면 통째로 비우는 설계라 다른 스레드가 비운 키는 남지 않을 수 있다. 크기가 묶이고 깨지지 않는 것만 본다. */
        @Test
        void 상한을_넘겨_동시에_찍어도_크기는_상한_근처로_묶이고_예외가_없다() throws Exception {
            EnqueueLatch latch = EnqueueLatch.covering(4, Duration.ofSeconds(5));

            // 라운드마다 새 키를 넣어 128개 — 비우는 처리가 없으면 크기가 128 이 되어 단언이 깨진다
            for (int round = 0; round < 4; round++) {
                int offset = round * THREADS;
                concurrently(i -> latch.mark("p" + (offset + i), 100));
            }

            assertThat(latch.size()).isLessThanOrEqualTo(4 + THREADS);
            latch.mark("last", 100);
            assertThat(latch.latched("last", 100)).isTrue();
        }

        private void concurrently(IntConsumer task) throws Exception {
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(THREADS);
            try {
                List<Future<?>> results = new ArrayList<>();
                for (int i = 0; i < THREADS; i++) {
                    int index = i;
                    results.add(pool.submit(() -> {
                        start.await();
                        task.accept(index);
                        return null;
                    }));
                }
                start.countDown();
                for (Future<?> result : results) {
                    result.get(5, TimeUnit.SECONDS);
                }
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    void 덮을_기간이_없으면_래치가_없는_것과_같아_막는다() {
        assertThatThrownBy(() -> EnqueueLatch.covering(10, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }
}
