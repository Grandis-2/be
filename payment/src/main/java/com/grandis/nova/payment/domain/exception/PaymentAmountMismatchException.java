package com.grandis.nova.payment.domain.exception;

/**
 * 승인 요청의 금액이 결제창을 열 때 정한 금액과 다르다. 위조된 요청일 수 있어 결제사에 보내지 않는다.
 * 메시지에 금액을 싣지 않는다 — 응답 · 로그로 흘러도 정한 금액이 드러나지 않게.
 */
public class PaymentAmountMismatchException extends RuntimeException {

    private final Long transactionId;

    public PaymentAmountMismatchException(Long transactionId) {
        super("결제 금액이 결제창을 열 때와 다르다: transactionId=" + transactionId);
        this.transactionId = transactionId;
    }

    public Long getTransactionId() {
        return transactionId;
    }
}
