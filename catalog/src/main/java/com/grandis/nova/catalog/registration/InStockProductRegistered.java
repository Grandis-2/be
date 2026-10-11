package com.grandis.nova.catalog.registration;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.grandis.nova.catalog.outbox.AggregateType;
import com.grandis.nova.catalog.outbox.OutboundEventType;
import com.grandis.nova.catalog.outbox.OutboxMessage;

import java.util.List;
import java.util.UUID;

/**
 * 일반 판매 상품을 등록했다 — order 가 옵션별 초기 재고를 만든다(order-events). 계약: contracts/catalog-events.md.
 * items 는 order 의 재고 초기화 API(POST /api/v1/admin/products/{id}/stock)의 본문과 같은 모양이라, 받는 쪽이 같은 초기화를 부르면 된다
 * (행이 없는 옵션만 만들고 있는 옵션은 그대로 둔다 — 같은 이벤트를 두 번 받거나 그사이 관리자가 고친 재고를 덮지 않는다).
 * 상품 id 는 봉투의 aggregateId 로만 간다.
 *
 * @param items 등록한 옵션 전부의 초기 재고(일반 상품은 옵션마다 필수, 0 허용)
 */
public record InStockProductRegistered(
        @JsonIgnore UUID productId,
        List<Item> items
) implements OutboxMessage {

    public InStockProductRegistered {
        items = List.copyOf(items);
    }

    @Override
    public OutboundEventType eventType() {
        return OutboundEventType.IN_STOCK_PRODUCT_REGISTERED;
    }

    @Override
    public AggregateType aggregateType() {
        return AggregateType.PRODUCT;
    }

    @Override
    public UUID aggregateId() {
        return productId;
    }

    public record Item(UUID optionId, int stockTotal) {
    }
}
