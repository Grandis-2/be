package com.grandis.nova.order.outbox;

import com.grandis.nova.common.outbox.OutboxEventType;

/** order 가 발행하는 이벤트 종류와 논리 목적지. order 아웃박스 표(order_outbox_events)에 적는 종류 전부다. */
public enum OutboundEventType implements OutboxEventType {

    PREORDER_ORDER_SETTLED("preorder-events"),
    ORDER_REFUND_REQUESTED("payment-events"),
    /** 응모비를 결제한 응모 — worker 가 받아 Mock(추첨)에 넘긴다(contracts/draw-entry-sync.md). */
    DRAW_ENTRY_PAID("draw-register"),
    /** 회차의 추첨 요청 — worker 가 Mock 과 응모 수를 대조한 뒤 추첨을 맡긴다(contracts/draw-entry-sync.md). */
    DRAW_REQUESTED("draw-register"),
    /** 회원 알림 한 건 — member 가 받아 저장한다(contracts/notification-events.md). */
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
