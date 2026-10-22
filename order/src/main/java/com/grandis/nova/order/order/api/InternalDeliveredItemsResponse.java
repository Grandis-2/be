package com.grandis.nova.order.order.api;

import com.grandis.nova.order.order.domain.model.OrderLine;
import com.grandis.nova.order.order.query.OrderQueryService;
import com.grandis.nova.order.order.query.OrderView;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 배송 완료된 구매 주문의 주문상품(내부 API 응답). 판정 없이 사실만 준다 — catalog 가 리뷰를 쓸 수 있는 것을 고른다.
 *
 * @param items     주문 최신순, 한 주문 안은 주문상품 id 순
 * @param truncated 최근 주문 상한({@link OrderQueryService#DELIVERED_PURCHASES_MAX})보다 오래된 주문이 더 있어 빠졌다
 */
public record InternalDeliveredItemsResponse(List<Item> items, boolean truncated) {

    static InternalDeliveredItemsResponse from(OrderQueryService.DeliveredItems found) {
        return new InternalDeliveredItemsResponse(found.items().stream().map(Item::of).toList(), found.truncated());
    }

    /**
     * @param productTitle 주문 당시 상품명(order_items.product_title_snapshot)
     * @param optionTitle  주문 당시 옵션명(order_items.option_title_snapshot)
     * @param imageUrl     주문 때 그 옵션의 사진. 사진을 남기기 전에 만든 주문 · 사진이 없는 옵션은 null
     * @param lineAmount   단가 × 수량 + 보증가 × 보증 수량
     * @param orderedAt    주문 시각(orders.created_at)
     */
    public record Item(UUID orderItemId, UUID orderId, UUID productId, UUID optionId, String productTitle, String optionTitle,
                       String imageUrl, int quantity, BigDecimal lineAmount, Instant orderedAt) {

        static Item of(OrderView.Item view) {
            OrderLine line = view.item().line();
            return new Item(view.item().id(), view.order().id(), line.productId(), line.optionId(), line.productTitle(),
                    line.optionTitle(), line.imageUrl(), line.quantity().value(), line.subtotal().amount(), view.order().createdAt());
        }
    }
}
