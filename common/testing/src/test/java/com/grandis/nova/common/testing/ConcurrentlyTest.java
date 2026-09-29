package com.grandis.nova.common.testing;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConcurrentlyTest {

    @Test
    void 값과_예외를_작업_순서대로_모은다() throws Exception {
        List<Concurrently.Outcome<Integer>> outcomes = Concurrently.run(3, i -> () -> {
            if (i == 1) {
                throw new IllegalStateException("boom");
            }
            return i;
        });

        assertThat(outcomes).extracting(Concurrently.Outcome::value).containsExactly(0, null, 2);
        assertThat(outcomes.get(1).error()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 작업을_만들다_실패해도_먼저_제출한_스레드를_남기지_않는다() {
        CountDownLatch neverOpened = new CountDownLatch(1);

        assertThatThrownBy(() -> Concurrently.run(3, i -> {
            if (i == 2) {
                throw new IllegalArgumentException("작업 생성 실패");
            }
            return () -> neverOpened.await(1, TimeUnit.HOURS);
        })).isInstanceOf(IllegalArgumentException.class);
    }
}
