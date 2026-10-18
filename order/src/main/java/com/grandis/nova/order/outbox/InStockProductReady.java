package com.grandis.nova.order.outbox;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.Objects;
import java.util.UUID;

/**
 * 일반 판매 상품의 판매 준비(contracts/order-catalog-events.md). 그 상품 옵션의 재고 행을 새로 만든 트랜잭션이 보내고, catalog 가 받아
 * 상품을 "판매 준비됨" 으로 남긴다. 재고 행은 지우는 길이 없어 준비는 되돌아가지 않는다 — 그래서 행을 만들 때마다 보내고(중복 무해),
 * 수량은 싣지 않는다(수량은 GET /internal/stocks 로 읽는다). payload 는 빈 객체다.
 *
 * @param productId 상품 id. 봉투의 aggregateId 로만 나간다
 */
public record InStockProductReady(@JsonIgnore UUID productId) implements OutboxMessage {

    public InStockProductReady {
        Objects.requireNonNull(productId, "productId");
    }

    @Override
    public OutboundEventType eventType() {
        return OutboundEventType.IN_STOCK_PRODUCT_READY;
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
