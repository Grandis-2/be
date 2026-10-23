package com.grandis.nova.catalog.integration;

import com.grandis.nova.common.web.ApiResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * order 내부 API — 리뷰를 쓸 자격 확인 · 리뷰를 쓸 수 있는 주문상품 후보. 계약: contracts/order-internal.md.
 * 요청한 사용자의 토큰은 릴레이 인터셉터가 싣는다. order 가 토큰 주인의 주문상품인지 확인한다 — 없거나 남의 것이면 404 ORDER_ITEM_NOT_FOUND.
 * 그 밖의 404(경로 없음 · 공통 NOT_FOUND)와 403 은 연동 오류로 남긴다({@link InternalCalls}).
 */
@HttpExchange("/internal/order-items")
public interface OrderClient {

    @GetExchange("/{orderItemId}")
    ApiResponse<OrderItem> getOrderItem(@PathVariable UUID orderItemId);

    /** 토큰 주인의 배송 완료된 장바구니 · 바로 구매 주문의 주문상품(주문 최신순). */
    @GetExchange("/delivered")
    ApiResponse<DeliveredItems> getDeliveredItems();

    /** @param truncated order 가 최근 주문 상한까지만 읽어 오래된 주문이 빠졌다 */
    record DeliveredItems(List<DeliveredItem> items, boolean truncated) {
    }

    /**
     * @param productTitle 주문 당시 상품명
     * @param optionTitle  주문 당시 옵션명
     * @param imageUrl     주문 때 그 옵션의 사진. 없으면 null
     * @param lineAmount   단가 × 수량 + 보증가 × 보증 수량
     * @param orderedAt    주문 시각
     */
    record DeliveredItem(UUID orderItemId, UUID orderId, UUID productId, UUID optionId, String productTitle, String optionTitle,
                         String imageUrl, int quantity, BigDecimal lineAmount, Instant orderedAt) {
    }

    /**
     * @param optionTitle 주문 당시 옵션명(order_items.option_title_snapshot)
     * @param orderStatus 주문 상태 이름(DELIVERED 일 때만 리뷰를 쓴다)
     * @param orderSource 주문 경로 이름(PREORDER · BUY_NOW · CART · DRAW)
     */
    record OrderItem(UUID orderItemId, UUID orderId, UUID productId, UUID optionId, String optionTitle,
                     String orderStatus, String orderSource) {
    }
}
