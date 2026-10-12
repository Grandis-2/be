package com.grandis.nova.worker.event;

/** worker 가 preorder 에 돌려주는 결과 이벤트. 모두 preorder-events 큐로 간다. */
public enum ResultEventType {
    /** 외부에 반영됐다. */
    EXTERNAL_JOB_SUCCEEDED,
    /** 이번 차례는 실패했고 다음 차례를 잡았다 — 기록용이다. */
    EXTERNAL_JOB_ATTEMPTED,
    /** 더 시도하지 않는다. */
    EXTERNAL_JOB_FAILED;

    public static final String QUEUE = "preorder-events";
    public static final String AGGREGATE_TYPE = "PREORDER_SYNC_JOB";
}
