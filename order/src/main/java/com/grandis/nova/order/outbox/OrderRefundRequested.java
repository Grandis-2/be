package com.grandis.nova.order.outbox;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * 그 주문의 결제를 전액 환불해 달라는 요청. payment 의 {@code event.OrderRefundRequested} 가 받는다. 두 곳에서 적는다 — 결제된 사전예약
 * 주문을 취소 중으로 바꾼 트랜잭션(SettlePreorderCancelService), 결과를 모른 채 취소한 장바구니 · 바로 구매 주문에 그 결제창의 승인이 늦게 온
 * 트랜잭션(PaymentResults, 자동 환불 요청 시각과 함께). 어느 쪽이든 그 상태 변경과 함께 커밋된다(아웃박스). 사용자 토큰 없는 서비스 간 HTTP 는 쓰지 않는다(D15).
 *
 * payload: {amount}. 환불 금액 · 결제 키는 payment 가 자기 결제 기록에서 읽는다 — 금액은 대조용이다.
 *
 * @param orderId 주문 id(결제 대상 id). 봉투의 aggregateId 로만 나가고 payload 에는 싣지 않는다
 * @param amount  주문 총액
 */
public record OrderRefundRequested(@JsonIgnore UUID orderId, BigDecimal amount) implements OutboxMessage {

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
    public UUID aggregateId() {
        return orderId;
    }
}
