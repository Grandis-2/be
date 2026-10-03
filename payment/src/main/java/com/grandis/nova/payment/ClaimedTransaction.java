package com.grandis.nova.payment;

import com.grandis.nova.payment.domain.model.PaymentTransaction;

import java.util.Objects;

/**
 * 리스를 쥔 결제 거래. {@link PaymentLedger} 의 start · claim 만 만든다(생성자가 이 패키지 밖에 닫혀 있다).
 *
 * 결과 반영({@link PaymentLedger#resolve})은 이 타입만 받는다. 조회로 읽은 PROCESSING 스냅샷에도 리스 표식이 들어 있어,
 * 그것을 받으면 선점하지 않은 코드(폴링 · 조회 경로)가 리스를 쥔 작업자 대신 결과를 반영할 수 있다.
 */
public final class ClaimedTransaction {

    private final PaymentTransaction transaction;

    ClaimedTransaction(PaymentTransaction transaction) {
        this.transaction = Objects.requireNonNull(transaction, "transaction");
        if (transaction.leaseToken() == null) {
            throw new IllegalArgumentException("리스를 쥐지 않은 거래다: " + transaction);
        }
    }

    /** 선점 직후의 스냅샷. 결제사에 보낼 값(결제사 주문 번호 · 결제 키 · 금액 · 멱등 키)을 여기서 읽는다. */
    public PaymentTransaction transaction() {
        return transaction;
    }

    @Override
    public String toString() {
        return "ClaimedTransaction[" + transaction + "]";
    }
}
