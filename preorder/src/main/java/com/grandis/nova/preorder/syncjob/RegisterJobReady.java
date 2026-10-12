package com.grandis.nova.preorder.syncjob;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonRawValue;
import com.grandis.nova.preorder.outbox.AggregateType;
import com.grandis.nova.preorder.outbox.OutboundEventType;
import com.grandis.nova.preorder.outbox.OutboxMessage;

import java.util.UUID;

/**
 * 외부 등록을 보내라는 메시지. 접수 때 한 번, 관리자 재처리 때마다 다시 나간다.
 * worker 는 DB 를 읽지 않으므로 보낼 내용(request)을 통째로 싣는다 — 작업에 저장한 JSON 그대로다.
 * jobType 은 record 칸이 아니라 고정값이다 — 칸으로 두면 이벤트 종류와 다른 값을 넣을 수 있다.
 */
public record RegisterJobReady(UUID syncJobId, String preorderId, @JsonRawValue String request, SyncActor actor)
        implements OutboxMessage {

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
    public UUID aggregateId() {
        return syncJobId;
    }
}
