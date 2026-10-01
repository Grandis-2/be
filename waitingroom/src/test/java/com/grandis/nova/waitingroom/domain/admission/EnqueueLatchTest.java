package com.grandis.nova.waitingroom.domain.admission;

import org.junit.jupiter.api.Test;

import java.time.Duration;

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

    @Test
    void 덮을_기간이_없으면_래치가_없는_것과_같아_막는다() {
        assertThatThrownBy(() -> EnqueueLatch.covering(10, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }
}
