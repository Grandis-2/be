package com.grandis.nova.payment.domain.exception;

import com.grandis.nova.payment.vo.PaymentTarget;

/**
 * 대상의 마지막 환불이 확정 실패했다. 같은 환불 요청을 다시 받아도 새 REFUND 를 자동으로 열지 않는다(결정 33) — DB 는 허용하지만
 * 확정 실패(환불 기한 초과 등)는 다시 보내도 같고, 다시 열지는 사람이 정한다. 받는 쪽은 이것을 "이미 끝남"으로 다룰 수 있다.
 * 호출자의 트랜잭션은 rollback-only 다.
 */
public class RefundFailedException extends RuntimeException {

    private final PaymentTarget target;

    public RefundFailedException(PaymentTarget target) {
        super("대상의 마지막 환불이 확정 실패했다: " + target);
        this.target = target;
    }

    public PaymentTarget getTarget() {
        return target;
    }
}
