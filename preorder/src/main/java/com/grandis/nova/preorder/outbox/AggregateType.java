package com.grandis.nova.preorder.outbox;

/** 소비자가 원장을 다시 읽을 대상. outbox_events.aggregate_type 에 이름 그대로 들어간다. */
public enum AggregateType {
    PREORDER_SYNC_JOB,
    PREORDER
}
