package com.grandis.nova.catalog.outbox;

import com.grandis.nova.common.outbox.OutboxEventType;

/** catalog 가 발행하는 이벤트 종류와 논리 목적지(큐). catalog_outbox_events.event_type 에 이름 그대로 들어간다. 계약: contracts/catalog-events.md. */
public enum OutboundEventType implements OutboxEventType {
    /** 사전예약 상품을 등록했다 — preorder 가 회차 · 배송 차수를 만든다. */
    PREORDER_PRODUCT_REGISTERED("preorder-events"),
    /** 일반 판매 상품을 등록했다 — order 가 옵션별 초기 재고를 만든다. */
    IN_STOCK_PRODUCT_REGISTERED("order-events"),
    /** 사전예약 오픈 뒤 판매 중지(회차 취소)를 접수했다 — preorder 가 회차를 마감하고 진행 중 예약의 취소를 시작한다. */
    PREORDER_CAMPAIGN_CANCELED("preorder-events");

    private final String destination;

    OutboundEventType(String destination) {
        this.destination = destination;
    }

    @Override
    public String destination() {
        return destination;
    }
}
