package com.grandis.nova.order.order.api;

import com.grandis.nova.order.order.domain.enums.OrderStatus;
import com.grandis.nova.order.order.domain.model.Order;

import java.util.UUID;

/**
 * 주문 취소 응답(명세 POST /orders/{orderId}/cancel).
 *
 * @param orderId orders.id
 */
public record CancelOrderResponse(UUID orderId, OrderStatus status) {

    static CancelOrderResponse of(Order order) {
        return new CancelOrderResponse(order.id(), order.status());
    }
}
