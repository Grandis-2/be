package com.grandis.nova.payment.event;

import java.math.BigDecimal;

/**
 * ORDER_REFUND_REQUESTED 의 payload(order outbox.OrderRefundRequested 가 보낸다). 주문 id 는 payload 에 없고 봉투의 aggregateId 로만 온다.
 *
 * 환불 금액 · 결제 키는 payment 가 자기 결제 기록에서 읽는다 — 호출자가 보낸 값으로 환불하지 않는다(D15).
 *
 * @param amount 주문 총액. 결제 금액과 대조만 한다(다르면 ERROR, 환불은 결제 금액으로)
 */
public record OrderRefundRequested(BigDecimal amount) {

    public OrderRefundRequested {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("환불 요청의 주문 총액이 없거나 0 이하다: " + amount);
        }
    }
}
