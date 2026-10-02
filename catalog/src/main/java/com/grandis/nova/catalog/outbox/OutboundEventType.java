package com.grandis.nova.catalog.outbox;

/** catalog 가 발행하는 이벤트 종류와 논리 목적지(큐). 계약: contracts/catalog-events.md. */
public enum OutboundEventType {
    /** 사전예약 상품을 등록했다 — preorder 가 회차 · 배송 차수를 만든다. */
    PREORDER_PRODUCT_REGISTERED("preorder-events"),
    /** 일반 판매 상품을 등록했다 — order 가 옵션별 초기 재고를 만든다. */
    IN_STOCK_PRODUCT_REGISTERED("order-events");

    private final String destination;

    OutboundEventType(String destination) {
        this.destination = destination;
    }

    public String destination() {
        return destination;
    }
}
