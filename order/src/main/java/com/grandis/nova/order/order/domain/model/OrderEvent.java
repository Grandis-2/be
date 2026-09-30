package com.grandis.nova.order.order.domain.model;

import com.grandis.nova.order.order.domain.enums.OrderStatus;
import com.grandis.nova.order.order.vo.EventCause;

import java.time.Instant;
import java.util.Objects;

/**
 * 주문 이력 한 줄. 추가 전용이다. 순서는 시각이 아니라 eventSequence 로 판정한다 —
 * 번호는 주문 행의 카운터에서 받는다.
 *
 * @param fromStatus 생성 이력이면 null
 */
public record OrderEvent(
        Long orderId,
        long eventSequence,
        OrderStatus fromStatus,
        OrderStatus toStatus,
        EventCause cause,
        Instant createdAt
) {

    /** 생성 이력의 번호. 주문 행의 카운터도 이 값으로 시작한다. */
    public static final long FIRST_SEQUENCE = 1;

    public OrderEvent {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(toStatus, "toStatus");
        Objects.requireNonNull(cause, "cause");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    /** 주문 생성 이력(번호 1, from 없음). */
    public static OrderEvent placed(Long orderId, EventCause cause, Instant now) {
        return new OrderEvent(orderId, FIRST_SEQUENCE, null, OrderStatus.AWAITING_PAYMENT, cause, now);
    }
}
