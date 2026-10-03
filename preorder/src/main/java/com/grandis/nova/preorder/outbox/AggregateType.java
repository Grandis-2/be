package com.grandis.nova.preorder.outbox;

import com.grandis.nova.common.outbox.OutboxAggregateType;

/** 소비자가 원장을 다시 읽을 대상. preorder_outbox_events.aggregate_type 에 이름 그대로 들어간다. */
public enum AggregateType implements OutboxAggregateType {
    PREORDER_SYNC_JOB,
    PREORDER,
    PREORDER_CAMPAIGN
}
