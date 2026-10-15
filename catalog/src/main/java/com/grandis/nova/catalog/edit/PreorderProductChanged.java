package com.grandis.nova.catalog.edit;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.grandis.nova.catalog.outbox.AggregateType;
import com.grandis.nova.catalog.outbox.OutboundEventType;
import com.grandis.nova.catalog.outbox.OutboxMessage;
import com.grandis.nova.catalog.product.Product;
import com.grandis.nova.catalog.product.SaleStatus;

import java.util.UUID;

/**
 * 사전예약 상품을 관리자가 고쳤다(preorder-events). 계약: contracts/catalog-events.md.
 *
 * preorder 는 받으면 접수용 상품 사본(캐시)을 비우고, 회차 행의 상품명 · 판매 상태 · 공개 여부 사본을 번호가 더 클 때만 덮는다.
 * 무엇이 바뀌었는지는 싣지 않는다 — 수정마다 지금 값 셋과 번호를 그대로 싣는다(옵션만 고쳐도 같은 번호로 다시 간다). 상품 id 는 봉투의 aggregateId 로만 싣는다.
 *
 * @param listingVersion 셋의 변경 번호({@link Product#getListingVersion()})
 */
public record PreorderProductChanged(@JsonIgnore UUID productId, String title, SaleStatus status, boolean visible,
                                     long listingVersion) implements OutboxMessage {

    static PreorderProductChanged of(Product product) {
        return new PreorderProductChanged(product.getId(), product.getTitle(), product.getStatus(), product.isVisible(),
                product.getListingVersion());
    }

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
