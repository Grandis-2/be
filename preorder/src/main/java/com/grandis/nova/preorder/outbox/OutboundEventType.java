package com.grandis.nova.preorder.outbox;

import com.grandis.nova.common.outbox.OutboxEventType;

/** preorder 가 발행하는 이벤트 종류와 논리 목적지. preorder_outbox_events.event_type 에 이름 그대로 들어간다. */
public enum OutboundEventType implements OutboxEventType {

    REGISTER_JOB_READY("preorder-register"),
    CANCEL_JOB_READY("preorder-cancel"),
    PREORDER_CANCEL_REQUESTED("order-events"),
    PREORDER_CAMPAIGN_CHANGED("waitingroom-events"),
    PREORDER_CAMPAIGN_CLOSED("catalog-events"),
    NOTIFICATION_REQUESTED("notification");

    private final String destination;

    OutboundEventType(String destination) {
        this.destination = destination;
    }

    @Override
    public String destination() {
        return destination;
    }
}
