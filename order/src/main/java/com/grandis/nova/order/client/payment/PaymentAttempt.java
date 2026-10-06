package com.grandis.nova.order.client.payment;

import java.math.BigDecimal;

/**
 * payment PaymentAttemptResponse 와 같은 모양 — 결제창을 열 값.
 *
 * @param providerOrderId 결제사(토스)에 알릴 주문 번호. 프론트가 결제위젯의 orderId 로 쓴다
 * @param amount          결제창에 띄울 금액(원)
 */
public record PaymentAttempt(String providerOrderId, BigDecimal amount) {
}
