package com.grandis.nova.preorder.outbox;

import java.util.Arrays;
import java.util.List;

/** preorder 가 발행하는 이벤트 종류와 논리 목적지. 릴레이는 이 종류만 다시 보낸다. */
public enum OutboundEventType {

    REGISTER_JOB_READY("preorder-register"),
    SYNC_JOB_REPROCESS_REQUESTED("preorder-register"),
    CANCEL_JOB_READY("preorder-cancel"),
    PREORDER_CANCEL_REQUESTED("order-events"),
    PREORDER_CAMPAIGN_CHANGED("waitingroom-events");

    private final String destination;

    OutboundEventType(String destination) {
        this.destination = destination;
    }

    public String destination() {
        return destination;
    }

    /** preorder_outbox_events.event_type 에 적힌 값들. preorder 가 발행 · 재발행하는 행을 가를 때 쓴다. */
    public static List<String> names() {
        return Arrays.stream(values()).map(Enum::name).toList();
    }
}
