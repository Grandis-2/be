package com.grandis.nova.preorder.syncjob;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonRawValue;
import com.grandis.nova.preorder.outbox.AggregateType;
import com.grandis.nova.preorder.outbox.OutboundEventType;
import com.grandis.nova.preorder.outbox.OutboxMessage;

import java.util.UUID;

/**
 * 외부 취소를 보내라는 메시지. 주문 정리가 끝난 뒤 한 번, 관리자 재처리 때마다 다시 나간다. 보낼 내용(request)은 작업에 저장한 JSON 그대로다.
 * run 은 작업의 실행 회차다 — worker 가 결과에 그대로 돌려줘 지난 실행의 결과를 가린다. jobType 은 고정값이다.
 */
public record CancelJobReady(UUID syncJobId, String preorderId, @JsonRawValue String request, SyncActor actor,
                             int run) implements OutboxMessage {

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
