package com.grandis.nova.common.sqs;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BatchConsumerSettingsTest {

    static final Duration VISIBILITY = Duration.ofMinutes(2);
    static final Duration IDLE_WAIT = Duration.ofMillis(100);
    static final Duration STOP_TIMEOUT = Duration.ofSeconds(30);

    @Test
    void SQS_가_받는_범위_밖이면_만들지_않는다() {
        assertThatCode(() -> settings(20, VISIBILITY)).doesNotThrowAnyException();
        assertThatThrownBy(() -> settings(21, VISIBILITY)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settings(-1, VISIBILITY)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settings(20, Duration.ofHours(13))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settings(20, Duration.ofMillis(500))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 큐_이름과_대기_시간들이_있어야_한다() {
        assertThatThrownBy(() -> new BatchConsumerSettings(" ", 20, VISIBILITY, IDLE_WAIT, STOP_TIMEOUT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BatchConsumerSettings("preorder-register", 20, null, IDLE_WAIT, STOP_TIMEOUT))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("visibility");
        assertThatThrownBy(() -> new BatchConsumerSettings("preorder-register", 20, VISIBILITY, Duration.ZERO,
                STOP_TIMEOUT)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("idle-wait");
        assertThatThrownBy(() -> new BatchConsumerSettings("preorder-register", 20, VISIBILITY, IDLE_WAIT,
                Duration.ZERO)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("stop-timeout");
    }

    @Test
    void 돌려보낼_시간은_0에서_12시간까지다() {
        assertThatCode(() -> MessageOutcome.returnAfter(Duration.ZERO)).doesNotThrowAnyException();
        assertThatCode(() -> MessageOutcome.returnAfter(Duration.ofHours(12))).doesNotThrowAnyException();
        assertThatThrownBy(() -> MessageOutcome.returnAfter(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MessageOutcome.returnAfter(Duration.ofHours(12).plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MessageOutcome.returnAfter(null)).isInstanceOf(IllegalArgumentException.class);
    }

    private static BatchConsumerSettings settings(int waitSeconds, Duration visibility) {
        return new BatchConsumerSettings("preorder-register", waitSeconds, visibility, IDLE_WAIT, STOP_TIMEOUT);
    }
}
