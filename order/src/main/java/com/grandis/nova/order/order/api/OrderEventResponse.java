package com.grandis.nova.order.order.api;

import com.grandis.nova.order.order.domain.enums.EventActor;
import com.grandis.nova.order.order.domain.enums.OrderStatus;
import com.grandis.nova.order.order.domain.model.OrderEvent;

import java.time.Instant;

/**
 * 주문 이력 한 줄. 순서는 시각이 아니라 eventSequence 다.
 * 사용자에게는 {@link #forCustomer} 로 낸다 — 관리자 사유는 내부 기록이라 가린다(NV-45 이월 24번). 누가 · 무엇을 했는지는 그대로다.
 */
public record OrderEventResponse(
        long eventSequence,
        OrderStatus fromStatus,
        OrderStatus toStatus,
        EventActor actor,
        String reason,
        Instant createdAt
) {

    public static OrderEventResponse forAdmin(OrderEvent event) {
        return of(event, event.cause().reason());
    }

    public static OrderEventResponse forCustomer(OrderEvent event) {
        return of(event, event.cause().actor() == EventActor.ADMIN ? null : event.cause().reason());
    }

    private static OrderEventResponse of(OrderEvent event, String reason) {
        return new OrderEventResponse(event.eventSequence(), event.fromStatus(), event.toStatus(),
                event.cause().actor(), reason, event.createdAt());
    }
}
