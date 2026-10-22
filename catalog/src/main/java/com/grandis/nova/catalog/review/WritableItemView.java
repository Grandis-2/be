package com.grandis.nova.catalog.review;

import com.grandis.nova.catalog.integration.OrderClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 리뷰를 쓸 수 있는 주문상품 하나. 이름 · 사진 · 금액은 주문 당시 값이다(order 가 남긴 스냅샷).
 *
 * @param imageUrl   주문 때 그 옵션의 사진. 사진을 남기기 전에 만든 주문 · 사진이 없는 옵션은 null
 * @param lineAmount 단가 × 수량 + 보증가 × 보증 수량
 * @param orderedAt  주문 시각
 */
public record WritableItemView(UUID orderItemId, UUID orderId, UUID productId, UUID optionId, String productTitle, String optionTitle,
                               String imageUrl, int quantity, BigDecimal lineAmount, Instant orderedAt) {

    static WritableItemView of(OrderClient.DeliveredItem item) {
        return new WritableItemView(item.orderItemId(), item.orderId(), item.productId(), item.optionId(), item.productTitle(),
                item.optionTitle(), item.imageUrl(), item.quantity(), item.lineAmount(), item.orderedAt());
    }
}
