package com.grandis.nova.order.draw.api;

import com.grandis.nova.order.draw.PreparedEntryPayment;

import java.math.BigDecimal;

/**
 * 응모비 결제위젯을 열 값 — 주문 결제의 같은 응답과 모양이 같다. 프론트는 이 값을 requestPayment 에 그대로 넘긴다.
 *
 * @param tossOrderId 결제위젯의 orderId. 준비마다 새로 발급된다 — 승인 요청에 그대로 돌려준다
 * @param amount      응모비(원)
 * @param orderName   결제창에 띄울 이름(회차 제목)
 */
public record EntryPaymentAttemptResponse(String tossOrderId, BigDecimal amount, String orderName) {

    static EntryPaymentAttemptResponse of(PreparedEntryPayment prepared) {
        return new EntryPaymentAttemptResponse(prepared.providerOrderId(), prepared.amount(), prepared.orderName());
    }
}
