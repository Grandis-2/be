package com.grandis.nova.order.outbox;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** common:outbox 이전 시: preorder outbox.publish.OutboxPropertiesTest 의 복사본이다. */
class OutboxPropertiesTest {

    @Test
    void rejectsNonPositiveConcurrencyBatchOrRelayAfter() {
        assertThatThrownBy(() -> new OutboxProperties(0, Duration.ofMinutes(1), 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutboxProperties(32, Duration.ofMinutes(1), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutboxProperties(32, Duration.ZERO, 100))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
