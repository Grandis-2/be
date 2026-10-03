package com.grandis.nova.common.outbox;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboxPropertiesTest {

    static final Duration MINUTE = Duration.ofMinutes(1);

    @Test
    void 동시_실행_묶음_크기는_1_이상_시간은_0_보다_커야_한다() {
        assertThatThrownBy(() -> new OutboxProperties(0, MINUTE, 100, MINUTE, MINUTE, "outbox"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutboxProperties(32, MINUTE, 0, MINUTE, MINUTE, "outbox"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutboxProperties(32, Duration.ZERO, 100, MINUTE, MINUTE, "outbox"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutboxProperties(32, MINUTE, 100, Duration.ZERO, MINUTE, "outbox"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutboxProperties(32, MINUTE, 100, MINUTE, Duration.ZERO, "outbox"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutboxProperties(32, MINUTE, 100, MINUTE, MINUTE, " "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
