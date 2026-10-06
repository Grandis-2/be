package com.grandis.nova.catalog.integration;

import com.grandis.nova.common.web.ApiResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

/**
 * order 내부 API — 리뷰를 쓸 자격 확인. 계약: contracts/order-internal.md.
 * 요청한 사용자의 토큰은 릴레이 인터셉터가 싣는다. order 가 토큰 주인의 주문상품인지 확인한다 — 없거나 남의 것이면 404 ORDER_ITEM_NOT_FOUND.
 * 그 밖의 404(경로 없음 · 공통 NOT_FOUND)와 403 은 연동 오류로 남긴다({@link InternalCalls}).
 */
@HttpExchange("/internal/order-items")
public interface OrderClient {

    @GetExchange("/{orderItemId}")
    ApiResponse<OrderItem> getOrderItem(@PathVariable Long orderItemId);

    /**
     * @param optionTitle 주문 당시 옵션명(order_items.option_title_snapshot)
     * @param orderStatus 주문 상태 이름(DELIVERED 일 때만 리뷰를 쓴다)
     * @param orderSource 주문 경로 이름(PREORDER · BUY_NOW · CART)
     */
    record OrderItem(Long orderItemId, Long orderId, Long productId, Long optionId, String optionTitle,
                     String orderStatus, String orderSource) {
    }
}
