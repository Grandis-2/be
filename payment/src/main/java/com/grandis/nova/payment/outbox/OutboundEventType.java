package com.grandis.nova.payment.outbox;

import com.grandis.nova.common.outbox.OutboxEventType;

/**
 * payment 가 발행하는 이벤트 종류와 논리 목적지. payment 아웃박스 표(payment_outbox_events)에 적는 종류 전부다.
 *
 * 릴레이는 종류로 목적지를 고른다 — 대상마다 받는 큐가 달라 종류 이름에 대상을 넣는다. 드로우 결제가 들어오면
 * DRAW_ENTRY_PAYMENT_SETTLED → draw 큐를 더한다. 이름은 받는 큐(order-events)의 다른 종류와 겹치지 않아야 한다.
 */
public enum OutboundEventType implements OutboxEventType {

    ORDER_PAYMENT_SETTLED("order-events");

    private final String destination;

    OutboundEventType(String destination) {
        this.destination = destination;
    }

    @Override
    public String destination() {
        return destination;
    }
}
