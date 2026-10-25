package com.grandis.nova.payment.domain.exception;

import com.grandis.nova.payment.vo.PaymentTarget;

import java.util.UUID;

/**
 * 관리자 조치가 본 마지막 환불이 지금의 마지막 환불이 아니거나, 다시 열 수 있는 상태(확정 실패)가 아니다 — 그사이 다른 조치 · 결과 반영이
 * 끼었다. 아무것도 바꾸지 않았다. 호출자의 트랜잭션은 rollback-only 다.
 */
public class RefundStateChangedException extends RuntimeException {

    private final PaymentTarget target;
    private final UUID expectedTransactionId;

    public RefundStateChangedException(PaymentTarget target, UUID expectedTransactionId) {
        super("마지막 환불이 관리자가 본 것과 다르다: target=" + target + ", expected=" + expectedTransactionId);
        this.target = target;
        this.expectedTransactionId = expectedTransactionId;
    }

    public PaymentTarget getTarget() {
        return target;
    }

    public UUID getExpectedTransactionId() {
        return expectedTransactionId;
    }
}
