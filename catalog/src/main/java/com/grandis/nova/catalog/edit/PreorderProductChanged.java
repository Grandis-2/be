package com.grandis.nova.catalog.edit;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.grandis.nova.catalog.outbox.AggregateType;
import com.grandis.nova.catalog.outbox.OutboundEventType;
import com.grandis.nova.catalog.outbox.OutboxMessage;

import java.util.UUID;

/**
 * 사전예약 상품을 관리자가 고쳤다 — preorder 가 모든 인스턴스의 접수용 상품 사본(캐시)을 비우고 다음 조회에서 catalog 에서 다시 받는다
 * (preorder-events). 계약: contracts/catalog-events.md.
 *
 * 무엇이 바뀌었는지는 싣지 않는다 — preorder 는 비우기만 하고 값은 내부 조회 API 로 다시 받는다. 상품 id 는 봉투의 aggregateId 로만 싣고
 * payload 는 빈 객체다.
 */
public record PreorderProductChanged(@JsonIgnore UUID productId) implements OutboxMessage {

    @Override
    public OutboundEventType eventType() {
        return OutboundEventType.PREORDER_PRODUCT_CHANGED;
    }

    @Override
    public AggregateType aggregateType() {
        return AggregateType.PRODUCT;
    }

    @Override
    public UUID aggregateId() {
        return productId;
    }
}
