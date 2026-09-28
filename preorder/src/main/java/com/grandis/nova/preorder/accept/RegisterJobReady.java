package com.grandis.nova.preorder.accept;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.grandis.nova.preorder.outbox.AggregateType;
import com.grandis.nova.preorder.outbox.OutboundEventType;
import com.grandis.nova.preorder.outbox.OutboxMessage;

/**
 * 접수 트랜잭션. worker 가 syncJobId 로 작업을 읽어 외부 등록을 보낸다.
 * jobType 은 record 칸이 아니라 고정값이다 — 칸으로 두면 이벤트 종류와 다른 값을 넣을 수 있다.
 */
public record RegisterJobReady(Long syncJobId, String preorderId) implements OutboxMessage {

    @JsonProperty
    public String jobType() {
        return "REGISTER";
    }

    @Override
    public OutboundEventType eventType() {
        return OutboundEventType.REGISTER_JOB_READY;
    }

    @Override
    public AggregateType aggregateType() {
        return AggregateType.PREORDER_SYNC_JOB;
    }

    @Override
    public Long aggregateId() {
        return syncJobId;
    }
}
