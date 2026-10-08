package com.grandis.nova.common.outbox;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboxPropertiesTest {

    static final Duration MINUTE = Duration.ofMinutes(1);
    static final Duration HOUR = Duration.ofHours(1);
    static final Duration WEEK = Duration.ofDays(7);

    @Test
    void 동시_실행_묶음_크기는_1_이상_시간은_0_보다_커야_한다() {
        assertThatThrownBy(() -> new OutboxProperties(0, MINUTE, 100, MINUTE, MINUTE, "outbox", WEEK, HOUR, 1000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutboxProperties(32, MINUTE, 0, MINUTE, MINUTE, "outbox", WEEK, HOUR, 1000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutboxProperties(32, Duration.ZERO, 100, MINUTE, MINUTE,
                "outbox", WEEK, HOUR, 1000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutboxProperties(32, MINUTE, 100, Duration.ZERO, MINUTE,
                "outbox", WEEK, HOUR, 1000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutboxProperties(32, MINUTE, 100, MINUTE, Duration.ZERO,
                "outbox", WEEK, HOUR, 1000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutboxProperties(32, MINUTE, 100, MINUTE, MINUTE, " ", WEEK, HOUR, 1000))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 보존_기간_정리_주기는_0_보다_크고_정리_묶음은_1_이상이어야_한다() {
        assertThatThrownBy(() -> new OutboxProperties(32, MINUTE, 100, MINUTE, MINUTE,
                "outbox", Duration.ZERO, HOUR, 1000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutboxProperties(32, MINUTE, 100, MINUTE, MINUTE,
                "outbox", WEEK, Duration.ZERO, 1000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutboxProperties(32, MINUTE, 100, MINUTE, MINUTE, "outbox", WEEK, HOUR, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 주기는_밀리초로_예약하므로_1ms_미만은_거절한다() {
        Duration underMillisecond = Duration.ofNanos(500_000);
        assertThatThrownBy(() -> new OutboxProperties(32, MINUTE, 100, MINUTE, underMillisecond,
                "outbox", WEEK, HOUR, 1000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutboxProperties(32, MINUTE, 100, MINUTE, MINUTE,
                "outbox", WEEK, underMillisecond, 1000))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
