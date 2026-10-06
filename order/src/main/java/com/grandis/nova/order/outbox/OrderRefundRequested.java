package com.grandis.nova.order.outbox;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 결제된 주문을 취소 중으로 바꿨다 — 그 주문의 결제를 전액 환불해 달라는 요청. payment 의 {@code event.OrderRefundRequested} 가 받는다.
 * 취소 중 전이와 같은 트랜잭션에 적는다(아웃박스가 둘을 함께 커밋한다). 사용자 토큰 없는 서비스 간 HTTP 는 쓰지 않는다(D15).
 *
 * payload: {amount}. 환불 금액 · 결제 키는 payment 가 자기 결제 기록에서 읽는다 — 금액은 대조용이다.
 *
 * @param orderId 주문 id(결제 대상 id). 봉투의 aggregateId 로만 나가고 payload 에는 싣지 않는다
 * @param amount  주문 총액
 */
public record OrderRefundRequested(@JsonIgnore Long orderId, BigDecimal amount) implements OutboxMessage {

    public OrderRefundRequested {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(amount, "amount");
    }

    @Override
    public OutboundEventType eventType() {
        return OutboundEventType.ORDER_REFUND_REQUESTED;
    }

    @Override
    public AggregateType aggregateType() {
        return AggregateType.ORDER;
    }

    @Override
    public Long aggregateId() {
        return orderId;
    }
}
