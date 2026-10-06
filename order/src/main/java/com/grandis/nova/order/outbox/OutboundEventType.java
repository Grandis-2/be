package com.grandis.nova.order.outbox;

import com.grandis.nova.common.outbox.OutboxEventType;

/** order 가 발행하는 이벤트 종류와 논리 목적지. order 아웃박스 표(order_outbox_events)에 적는 종류 전부다. */
public enum OutboundEventType implements OutboxEventType {

    PREORDER_ORDER_SETTLED("preorder-events"),
    ORDER_REFUND_REQUESTED("payment-events");

    private final String destination;

    OutboundEventType(String destination) {
        this.destination = destination;
    }

    @Override
    public String destination() {
        return destination;
    }
}
