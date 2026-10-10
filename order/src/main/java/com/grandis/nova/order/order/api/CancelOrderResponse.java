package com.grandis.nova.order.order.api;

import com.grandis.nova.order.order.domain.enums.OrderStatus;
import com.grandis.nova.order.order.domain.model.Order;

/**
 * 주문 취소 응답(명세 POST /orders/{orderId}/cancel).
 *
 * @param orderId order_token
 */
public record CancelOrderResponse(String orderId, OrderStatus status) {

    static CancelOrderResponse of(Order order) {
        return new CancelOrderResponse(order.orderToken().value(), order.status());
    }
}
