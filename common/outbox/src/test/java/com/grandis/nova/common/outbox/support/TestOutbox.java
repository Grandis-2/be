package com.grandis.nova.common.outbox.support;

import com.grandis.nova.common.outbox.OutboxAggregateType;
import com.grandis.nova.common.outbox.OutboxEventType;
import com.grandis.nova.common.outbox.OutboxMessage;

import java.util.UUID;

/** 시험용 서비스가 발행하는 이벤트 종류 · aggregate · 메시지. 서비스 코드와 같은 모양(enum + sealed + record)이다. */
public final class TestOutbox {

    public static final String TABLE = "it_outbox_events";

    private TestOutbox() {
    }

    public enum EventType implements OutboxEventType {
        ITEM_SETTLED("test-events"),
        ITEM_NOTIFIED("notification");

        private final String destination;

        EventType(String destination) {
            this.destination = destination;
        }

        @Override
        public String destination() {
            return destination;
        }
    }

    /** 등록하지 않은 종류. 기록이 거절돼야 한다. */
    public enum UnregisteredType implements OutboxEventType {
        ITEM_FORGOTTEN;

        @Override
        public String destination() {
            return "nowhere";
        }
    }

    public enum AggregateType implements OutboxAggregateType {
        ITEM
    }

    public sealed interface Message extends OutboxMessage permits ItemSettled, ItemForgotten {

        @Override
        AggregateType aggregateType();
    }

    /** @param itemId 봉투의 aggregateId 로만 나간다 */
    public record ItemSettled(@com.fasterxml.jackson.annotation.JsonIgnore UUID itemId, String result, Long sequence)
            implements Message {

        @Override
        public EventType eventType() {
            return EventType.ITEM_SETTLED;
        }

        @Override
        public AggregateType aggregateType() {
            return AggregateType.ITEM;
        }

        @Override
        public UUID aggregateId() {
            return itemId;
        }
    }

    public record ItemForgotten(UUID itemId) implements Message {

        @Override
        public UnregisteredType eventType() {
            return UnregisteredType.ITEM_FORGOTTEN;
        }

        @Override
        public AggregateType aggregateType() {
            return AggregateType.ITEM;
        }

        @Override
        public UUID aggregateId() {
            return itemId;
        }
    }
}
