package com.grandis.nova.order.outbox;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * 주문을 승인 중으로 바꿨다 — payment 의 {@code event.OrderPaymentAuthorizing} 이 받는다. 승인 중 전환과 같은 트랜잭션에 적는다
 * (아웃박스가 둘을 함께 커밋한다). 뒤이은 승인 요청이 payment 에 닿지 않으면 payment 가 이 알림으로 그 결제창의 승인을 복구에 넘긴다.
 * 전환이 되돌아가면 알림도 없다.
 *
 * payload: {providerOrderId, amount}. 결제 키는 싣지 않는다 — payment 가 결제창 확인 때 받아 둔 키를 쓴다. 금액은 payment 가 결제창 금액과
 * 대조만 한다(다르면 넘기지 않는다 — 승인 요청의 시작과 같은 대조).
 *
 * @param orderId         주문 id(결제 대상 id). 봉투의 aggregateId 로만 나가고 payload 에는 싣지 않는다
 * @param providerOrderId 승인 중으로 바꾼 결제창(결제사 주문 번호)
 * @param amount          주문의 저장 총액. 승인 요청에 싣는 값과 같다(PayableTarget.of(order))
 */
public record OrderPaymentAuthorizing(@JsonIgnore UUID orderId, String providerOrderId, BigDecimal amount)
        implements OutboxMessage {

    public OrderPaymentAuthorizing {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(providerOrderId, "providerOrderId");
        Objects.requireNonNull(amount, "amount");
    }

    @Override
    public OutboundEventType eventType() {
        return OutboundEventType.ORDER_PAYMENT_AUTHORIZING;
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
