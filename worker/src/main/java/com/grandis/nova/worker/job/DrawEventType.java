package com.grandis.nova.worker.job;

/** order 가 draw-register 큐로 보내는 럭키드로우 메시지의 종류(be 계약 contracts/draw-entry-sync.md). */
public enum DrawEventType {
    /** 응모비 결제가 끝난 응모 — Mock 에 응모를 넘긴다. aggregateId 는 응모 id. */
    DRAW_ENTRY_PAID,
    /** 마감 뒤 추첨할 차례 — 받은 응모 수를 대조하고 추첨을 맡긴다. aggregateId 는 회차 id. */
    DRAW_REQUESTED;

    public static final String QUEUE = "draw-register";
}
