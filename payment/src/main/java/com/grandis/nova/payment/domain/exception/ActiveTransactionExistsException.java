package com.grandis.nova.payment.domain.exception;

import com.grandis.nova.payment.domain.enums.TransactionType;
import com.grandis.nova.payment.vo.PaymentTarget;

/**
 * 같은 대상에 진행 중이거나 성공한 같은 유형의 거래가 이미 있다(uq_payment_tx_active). 결제사를 부르기 전에 막는다 —
 * CAPTURE 면 이미 결제 중 · 결제됨, REFUND 면 이미 환불 중 · 환불됨이다. 저장소가 제약 이름을 보고 이 예외로 바꾼다.
 * 호출자의 트랜잭션은 rollback-only 다.
 */
public class ActiveTransactionExistsException extends RuntimeException {

    private final PaymentTarget target;
    private final TransactionType type;

    public ActiveTransactionExistsException(PaymentTarget target, TransactionType type, Throwable cause) {
        super("대상에 진행 중이거나 성공한 거래가 이미 있다: target=%s, type=%s".formatted(target, type), cause);
        this.target = target;
        this.type = type;
    }

    public PaymentTarget getTarget() {
        return target;
    }

    public TransactionType getType() {
        return type;
    }
}
