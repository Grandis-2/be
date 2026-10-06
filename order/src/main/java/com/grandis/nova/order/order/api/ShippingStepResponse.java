package com.grandis.nova.order.order.api;

import com.grandis.nova.order.order.domain.enums.OrderStatus;
import com.grandis.nova.order.order.ship.ShippingAdvance;

/**
 * @param orderId 주문 공개 토큰
 * @param status  지금 상태
 * @param applied 이번 요청이 옮겼으면 true. 이미 그 단계였으면 false — 이력의 사유는 먼저 옮긴 요청의 것이다
 */
public record ShippingStepResponse(String orderId, OrderStatus status, boolean applied) {

    public static ShippingStepResponse of(ShippingAdvance advance) {
        return new ShippingStepResponse(advance.orderToken().value(), advance.status(), advance.applied());
    }
}
