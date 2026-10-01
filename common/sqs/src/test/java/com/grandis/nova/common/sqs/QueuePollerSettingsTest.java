package com.grandis.nova.common.sqs;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QueuePollerSettingsTest {

    @Test
    void SQS_가_받는_범위_밖이면_만들지_않는다() {
        assertThatCode(() -> settings(10, 20, Duration.ofMinutes(5))).doesNotThrowAnyException();
        assertThatThrownBy(() -> settings(11, 20, Duration.ofMinutes(5))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settings(10, 21, Duration.ofMinutes(5))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settings(10, 20, Duration.ofHours(13))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settings(10, 20, Duration.ofMillis(500))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 재시도_대기는_0보다_크고_상한_이하여야_한다() {
        assertThatThrownBy(() -> backoff(Duration.ZERO, Duration.ofMinutes(5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> backoff(Duration.ofMinutes(10), Duration.ofMinutes(5)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 큐_이름과_동시_수가_있어야_한다() {
        assertThatThrownBy(() -> new QueuePollerSettings(" ", 1, 20, 10, Duration.ofMinutes(5),
                Duration.ofSeconds(5), Duration.ofMinutes(5))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QueuePollerSettings("order-events", 0, 20, 10, Duration.ofMinutes(5),
                Duration.ofSeconds(5), Duration.ofMinutes(5))).isInstanceOf(IllegalArgumentException.class);
    }

    /** 직접 만드는 서비스도 NPE 대신 범위 설명이 담긴 오류를 받는다. */
    @Test
    void 대기_시간이_없으면_같은_진단_메시지로_막는다() {
        assertThatThrownBy(() -> new QueuePollerSettings("order-events", 1, 20, 10, null,
                Duration.ofSeconds(5), Duration.ofMinutes(5)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("visibility");
        assertThatThrownBy(() -> new QueuePollerSettings("order-events", 1, 20, 10, Duration.ofMinutes(5),
                null, Duration.ofMinutes(5)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("backoff-base");
        assertThatThrownBy(() -> new QueuePollerSettings("order-events", 1, 20, 10, Duration.ofMinutes(5),
                Duration.ofSeconds(5), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("backoff-max");
    }

    private static QueuePollerSettings settings(int maxMessages, int waitSeconds, Duration visibility) {
        return new QueuePollerSettings("order-events", 1, waitSeconds, maxMessages, visibility,
                Duration.ofSeconds(5), Duration.ofMinutes(5));
    }

    private static QueuePollerSettings backoff(Duration base, Duration max) {
        return new QueuePollerSettings("order-events", 1, 20, 10, Duration.ofMinutes(5), base, max);
    }
}
