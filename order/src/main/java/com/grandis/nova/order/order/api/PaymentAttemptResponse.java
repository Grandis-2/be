package com.grandis.nova.order.order.api;

import com.grandis.nova.order.order.pay.PreparedPayment;

import java.math.BigDecimal;

/**
 * 토스 결제위젯을 열 값. 프론트는 이 값을 requestPayment 에 그대로 넘긴다. customerKey 는 프론트가 만든다 — 싣지 않는다.
 *
 * @param tossOrderId 결제위젯의 orderId. 준비마다 새로 발급된다 — 승인 요청에 그대로 돌려준다
 * @param amount      결제 금액(원). 주문 총액이다
 * @param orderName   결제창에 띄울 주문명(최대 100자)
 */
public record PaymentAttemptResponse(String tossOrderId, BigDecimal amount, String orderName) {

    static PaymentAttemptResponse of(PreparedPayment prepared) {
        return new PaymentAttemptResponse(prepared.providerOrderId(), prepared.amount().amount(), prepared.orderName());
    }
}
