package com.grandis.nova.payment.domain.exception;

import com.grandis.nova.payment.vo.PaymentTarget;

/**
 * 환불하려는 대상의 결제가 이미 환불됐다. 환불 요청은 최소 1회 전달이라 재전송이 흔하다 — 받는 쪽은 이것을 "이미 끝남"(성공)으로
 * 다룰 수 있다. 결제가 아예 없는 경우(IllegalStateException, 호출 쪽 잘못)와 구분하려고 따로 둔다.
 * 호출자의 트랜잭션은 rollback-only 다.
 */
public class PaymentAlreadyRefundedException extends RuntimeException {

    private final PaymentTarget target;

    public PaymentAlreadyRefundedException(PaymentTarget target) {
        super("대상의 결제가 이미 환불됐다: " + target);
        this.target = target;
    }

    public PaymentTarget getTarget() {
        return target;
    }
}
