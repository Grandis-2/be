package com.grandis.nova.order.order.api;

import com.grandis.nova.order.order.domain.enums.EventActor;
import com.grandis.nova.order.order.domain.enums.OrderStatus;
import com.grandis.nova.order.order.domain.model.OrderEvent;

import java.time.Instant;

/** 주문 이력 한 줄. 순서는 시각이 아니라 eventSequence 다. */
public record OrderEventResponse(
        long eventSequence,
        OrderStatus fromStatus,
        OrderStatus toStatus,
        EventActor actor,
        String reason,
        Instant createdAt
) {

    public static OrderEventResponse from(OrderEvent event) {
        return new OrderEventResponse(event.eventSequence(), event.fromStatus(), event.toStatus(),
                event.cause().actor(), event.cause().reason(), event.createdAt());
    }
}
