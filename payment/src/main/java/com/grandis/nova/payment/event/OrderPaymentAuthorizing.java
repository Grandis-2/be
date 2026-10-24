package com.grandis.nova.payment.event;

import java.math.BigDecimal;

/**
 * ORDER_PAYMENT_AUTHORIZING 의 payload(order outbox.OrderPaymentAuthorizing 이 보낸다). 주문 id 는 payload 에 없고 봉투의 aggregateId 로만 온다.
 *
 * 결제 키는 싣지 않는다 — payment 가 결제창 확인(확보) 때 받아 둔 키를 쓴다.
 *
 * @param providerOrderId 주문을 승인 중으로 바꾼 결제창(결제사 주문 번호)
 * @param amount          알림을 적을 때의 주문 총액. 결제창 금액과 대조만 한다(다르면 넘기지 않는다)
 */
public record OrderPaymentAuthorizing(String providerOrderId, BigDecimal amount) {

    public OrderPaymentAuthorizing {
        if (providerOrderId == null || providerOrderId.isBlank()) {
            throw new IllegalArgumentException("승인 중 알림의 결제창 번호가 없다");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("승인 중 알림의 주문 총액이 없거나 0 이하다: " + amount);
        }
    }
}
