package com.grandis.nova.payment.domain.repository;

import com.grandis.nova.payment.domain.model.Payment;
import com.grandis.nova.payment.vo.PaymentTarget;

import java.util.Optional;

/** 성공한 결제 읽기 포트. 대상당 0~1건이다(uq_payment_target). */
public interface PaymentReader {

    Optional<Payment> findPaymentByTarget(PaymentTarget target);

    /**
     * 대상의 결제를 잠가 읽는다. 호출자의 트랜잭션이 끝날 때까지 같은 대상의 환불 열기 · 환불 결과 반영이 기다린다 —
     * "마지막 환불이 실패했나" 판정과 새 환불 INSERT 사이에 실패 확정이 끼지 않게(결정 33).
     * 원장만 부른다(PaymentArchitectureTest) — 잠금 순서(결제 행 → 거래 행)를 한 곳에서 지켜야 교착이 생기지 않는다.
     */
    Optional<Payment> lockPaymentByTarget(PaymentTarget target);
}
