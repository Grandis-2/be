package com.grandis.nova.order.order.api;

import com.grandis.nova.order.order.domain.enums.OrderSource;
import com.grandis.nova.order.order.domain.enums.OrderStatus;
import com.grandis.nova.order.order.domain.model.OrderLine;
import com.grandis.nova.order.order.query.OrderView;

/**
 * 주문상품 하나(내부 API 응답). 판정 없이 사실만 준다 — catalog 가 배송 완료 · 출처로 리뷰 가능 여부를 가린다.
 *
 * @param orderId     주문 내부 id(orders.id). 공개 주문 번호(order_token)가 아니다
 * @param optionTitle 주문 당시 옵션명(order_items.option_title_snapshot)
 * @param orderStatus orders.status 이름 그대로
 * @param orderSource orders.source 이름 그대로(PREORDER · BUY_NOW · CART)
 */
public record InternalOrderItemResponse(Long orderItemId, Long orderId, Long productId, Long optionId,
                                        String optionTitle, OrderStatus orderStatus, OrderSource orderSource) {

    static InternalOrderItemResponse from(OrderView.Item view) {
        OrderLine line = view.item().line();
        return new InternalOrderItemResponse(view.item().id(), view.order().id(), line.productId(), line.optionId(),
                line.optionTitle(), view.order().status(), view.order().source());
    }
}
