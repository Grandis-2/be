package com.grandis.nova.payment.domain.exception;

import java.util.UUID;

/**
 * 승인 요청이 가리킨 거래가 호출자가 말한 대상의 것이 아니다 — 다른 주문 · 응모의 결제창 번호를 보냈다.
 * 결제사에 보내지 않는다. 메시지에 대상을 싣지 않는다(다른 사용자의 대상이 응답 · 로그로 흐르지 않게).
 */
public class PaymentTargetMismatchException extends RuntimeException {

    private final UUID transactionId;

    public PaymentTargetMismatchException(UUID transactionId) {
        super("거래의 대상이 요청한 대상과 다르다: transactionId=" + transactionId);
        this.transactionId = transactionId;
    }

    public UUID getTransactionId() {
        return transactionId;
    }
}
