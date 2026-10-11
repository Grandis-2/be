package com.grandis.nova.preorder.event;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.grandis.nova.preorder.outbox.AggregateType;
import com.grandis.nova.preorder.outbox.OutboundEventType;
import com.grandis.nova.preorder.outbox.OutboxMessage;

import java.util.UUID;

/** 주문 정리가 끝난 뒤. worker 가 외부 취소를 보낸다. jobType 은 고정값이다. */
public record CancelJobReady(UUID syncJobId, String preorderId) implements OutboxMessage {

    @JsonProperty
    public String jobType() {
        return "CANCEL";
    }

    @Override
    public OutboundEventType eventType() {
        return OutboundEventType.CANCEL_JOB_READY;
    }

    @Override
    public AggregateType aggregateType() {
        return AggregateType.PREORDER_SYNC_JOB;
    }

    @Override
    public UUID aggregateId() {
        return syncJobId;
    }
}
