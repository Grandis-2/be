package com.grandis.nova.preorder.syncjob.application;

import com.grandis.nova.preorder.outbox.AggregateType;
import com.grandis.nova.preorder.outbox.OutboundEventType;
import com.grandis.nova.preorder.outbox.OutboxMessage;

import java.util.UUID;

/** 관리자 DLQ 재처리. worker 가 DEAD_LETTER 인 작업만 되돌린다. */
record SyncJobReprocessRequested(UUID syncJobId, String requestedBy) implements OutboxMessage {

    @Override
    public OutboundEventType eventType() {
        return OutboundEventType.SYNC_JOB_REPROCESS_REQUESTED;
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
