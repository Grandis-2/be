package com.grandis.nova.order.outbox;

import com.grandis.nova.common.outbox.OutboxEventType;

/** order 가 발행하는 이벤트 종류와 논리 목적지. order 아웃박스 표(order_outbox_events)에 적는 종류 전부다. */
public enum OutboundEventType implements OutboxEventType {

    PREORDER_ORDER_SETTLED("preorder-events"),
    /** 사전예약 주문 생성(결제 시작) — preorder 가 받아 "결제 진행 중" 으로 보인다(contracts/preorder-order-events.md). */
    PREORDER_PAYMENT_STARTED("preorder-events"),
    /** 사전예약 주문 결제 승인 — preorder 가 받아 예약을 확정한다(contracts/preorder-order-events.md). */
    PREORDER_PAYMENT_CONFIRMED("preorder-events"),
    ORDER_REFUND_REQUESTED("payment-events"),
    /** 주문을 승인 중으로 바꿈 — payment 가 받아 승인 요청이 닿지 않았으면 복구에 넘긴다(contracts/order-payment-events.md). */
    ORDER_PAYMENT_AUTHORIZING("payment-events"),
    /** 응모비를 결제한 응모 — worker 가 받아 Mock(추첨)에 넘긴다(contracts/draw-entry-sync.md). */
    DRAW_ENTRY_PAID("draw-register"),
    /** 회차의 추첨 요청 — worker 가 Mock 과 응모 수를 대조한 뒤 추첨을 맡긴다(contracts/draw-entry-sync.md). */
    DRAW_REQUESTED("draw-register"),
    /** 회원 알림 한 건 — member 가 받아 저장한다(contracts/notification-events.md). */
    NOTIFICATION_REQUESTED("notification"),
    /** 일반 판매 상품의 재고 행을 새로 만듦(판매 준비) — catalog 가 받아 상품을 판매 준비로 남긴다(contracts/order-catalog-events.md). */
    IN_STOCK_PRODUCT_READY("catalog-events"),
    /** 관리자 알림 한 건(드로우 반영 완료 · 지연) — member 가 받아 저장한다(contracts/admin-alert-events.md). */
    ADMIN_ALERT_REQUESTED("admin-alert");

    private final String destination;

    OutboundEventType(String destination) {
        this.destination = destination;
    }

    @Override
    public String destination() {
        return destination;
    }
}
