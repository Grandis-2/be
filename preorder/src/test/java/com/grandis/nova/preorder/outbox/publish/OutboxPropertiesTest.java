package com.grandis.nova.preorder.outbox.publish;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboxPropertiesTest {

    @Test
    void 동시_실행_상한_릴레이_묶음이_1보다_작거나_릴레이_대기_리스가_0_이하면_기동하지_않는다() {
        assertThatThrownBy(() -> new OutboxProperties(0, Duration.ofMinutes(1), 100, Duration.ofMinutes(10)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutboxProperties(32, Duration.ofMinutes(1), 0, Duration.ofMinutes(10)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutboxProperties(32, Duration.ZERO, 100, Duration.ofMinutes(10)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutboxProperties(32, Duration.ofMinutes(1), 100, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
