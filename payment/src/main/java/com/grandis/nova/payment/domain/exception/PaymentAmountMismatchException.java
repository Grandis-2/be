package com.grandis.nova.payment.domain.exception;

import java.util.UUID;

/**
 * 결제창을 열 때의 금액(호출자가 주장한 값)이 호출자의 저장 금액(주문 총액 · 응모비)과 다르다 — 결제창이 다른 금액으로
 * 열렸다는 뜻이라 결제사에 보내지 않는다. 대조 기준은 사용자가 승인 요청에 실은 금액이 아니다(PaymentLedger.start).
 * 메시지에 금액을 싣지 않는다 — 응답 · 로그로 흘러도 금액이 드러나지 않게.
 */
public class PaymentAmountMismatchException extends RuntimeException {

    private final UUID transactionId;

    public PaymentAmountMismatchException(UUID transactionId) {
        super("결제창을 열 때의 금액이 저장 금액과 다르다: transactionId=" + transactionId);
        this.transactionId = transactionId;
    }

    public UUID getTransactionId() {
        return transactionId;
    }
}
