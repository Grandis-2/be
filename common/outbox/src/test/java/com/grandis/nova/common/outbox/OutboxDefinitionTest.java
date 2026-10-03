package com.grandis.nova.common.outbox;

import com.grandis.nova.common.outbox.support.TestOutbox.EventType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboxDefinitionTest {

    /** 표 이름을 SQL 에 이어 붙이므로 식별자 모양만 받는다. */
    @ParameterizedTest
    @ValueSource(strings = {"", "Outbox", "1outbox", "outbox-events", "outbox events", "shop.outbox",
            "outbox;drop table x", "a2345678901234567890123456789012345678901234567890123456789012345"})
    void 소문자_숫자_밑줄_64자_밖의_표_이름은_받지_않는다(String table) {
        assertThatThrownBy(() -> new OutboxDefinition(table, List.of(EventType.values())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 종류가_없거나_이름이_겹치거나_목적지가_없으면_받지_않는다() {
        assertThatThrownBy(() -> new OutboxDefinition("order_outbox_events", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OutboxDefinition.of("order_outbox_events", EventType.ITEM_SETTLED, type("ITEM_SETTLED", "x")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OutboxDefinition.of("order_outbox_events", type("A", " ")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OutboxDefinition.of("order_outbox_events", type("A".repeat(51), "x")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 종류의_목적지를_찾고_모르는_종류는_거절한다() {
        OutboxDefinition definition = OutboxDefinition.of("order_outbox_events", EventType.values());

        assertThat(definition.table()).isEqualTo("order_outbox_events");
        assertThat(definition.destinationOf("ITEM_NOTIFIED")).isEqualTo("notification");
        assertThatThrownBy(() -> definition.destinationOf("ITEM_REMOVED"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ITEM_REMOVED");
    }

    private static OutboxEventType type(String name, String destination) {
        return new OutboxEventType() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String destination() {
                return destination;
            }
        };
    }
}
