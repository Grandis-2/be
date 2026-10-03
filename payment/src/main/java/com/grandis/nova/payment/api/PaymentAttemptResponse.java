package com.grandis.nova.payment.api;

import com.grandis.nova.payment.domain.model.PaymentTransaction;

import java.math.BigDecimal;

/**
 * 결제창을 열 값. 호출자는 이것을 결제사 결제창에 그대로 넘긴다.
 *
 * @param providerOrderId 결제사(토스)에 알릴 주문 번호. 이 거래에서 새로 발급했다
 * @param amount          결제창에 띄울 금액(원). 요청한 금액 그대로다
 */
public record PaymentAttemptResponse(String providerOrderId, BigDecimal amount) {

    static PaymentAttemptResponse from(PaymentTransaction opened) {
        return new PaymentAttemptResponse(opened.providerOrderId().value(), opened.amount().amount());
    }
}
