package com.grandis.nova.waitingroom.domain.admission;

import com.grandis.nova.waitingroom.domain.admission.SecondWindowLimiter.AcquireResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecondWindowLimiterTest {

    private final SecondWindowLimiter limiter = new SecondWindowLimiter(100);

    @Test
    void 모델_상한을_넘으면_모델_고갈이고_노드_예산은_깎지_않는다() {
        assertThat(limiter.tryAcquireAll("p1", 1, "node", 10, 0)).isEqualTo(AcquireResult.ACQUIRED);
        assertThat(limiter.tryAcquireAll("p1", 1, "node", 10, 0)).isEqualTo(AcquireResult.PRODUCT_EXHAUSTED);

        for (int i = 0; i < 9; i++) {
            assertThat(limiter.tryAcquireAll("p" + (i + 2), 1, "node", 10, 0)).isEqualTo(AcquireResult.ACQUIRED);
        }
        assertThat(limiter.tryAcquireAll("p99", 1, "node", 10, 0)).as("노드 몫 10 은 실제 통과 10 건만 깎였다")
                .isEqualTo(AcquireResult.GLOBAL_EXHAUSTED);
    }

    @Test
    void 노드_예산이_모자라면_모델_예산도_깎지_않는다() {
        assertThat(limiter.tryAcquireAll("p1", 2, "node", 1, 0)).isEqualTo(AcquireResult.ACQUIRED);
        assertThat(limiter.tryAcquireAll("p1", 2, "node", 1, 0)).isEqualTo(AcquireResult.GLOBAL_EXHAUSTED);

        assertThat(limiter.tryAcquireAll("p1", 2, "node-2", 1, 0)).as("p1 은 아직 한 번만 깎였다")
                .isEqualTo(AcquireResult.ACQUIRED);
        assertThat(limiter.tryAcquireAll("p1", 2, "node-3", 1, 0)).isEqualTo(AcquireResult.PRODUCT_EXHAUSTED);
    }

    @Test
    void 초가_바뀌면_다시_받고_지난_초가_와도_현재_창을_날리지_않는다() {
        limiter.tryAcquireAll("p1", 1, "node", 10, 5);

        assertThat(limiter.tryAcquireAll("p1", 1, "node", 10, 4)).isEqualTo(AcquireResult.PRODUCT_EXHAUSTED);
        assertThat(limiter.tryAcquireAll("p1", 1, "node", 10, 6)).isEqualTo(AcquireResult.ACQUIRED);
    }

    @Test
    void 키_자리가_모자라면_예산이_남아도_포화다() {
        SecondWindowLimiter small = new SecondWindowLimiter(3);

        assertThat(small.tryAcquireAll("p1", 5, "node", 50, 0)).isEqualTo(AcquireResult.ACQUIRED);
        assertThat(small.tryAcquireAll("p2", 5, "node", 50, 0)).isEqualTo(AcquireResult.ACQUIRED);
        assertThat(small.tryAcquireAll("p3", 5, "node", 50, 0)).isEqualTo(AcquireResult.KEY_SATURATED);
        assertThat(small.size()).isEqualTo(3);
    }

    @Test
    void 상한이_0_이면_받지_않는다() {
        assertThat(limiter.tryAcquireAll("p1", 0, "node", 10, 0)).isEqualTo(AcquireResult.PRODUCT_EXHAUSTED);
        assertThat(limiter.tryAcquireAll("p1", 1, "node", 0, 0)).isEqualTo(AcquireResult.GLOBAL_EXHAUSTED);
    }

    @Test
    void 두_예산의_키가_같으면_막는다() {
        assertThatThrownBy(() -> limiter.tryAcquireAll("same", 1, "same", 1, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
