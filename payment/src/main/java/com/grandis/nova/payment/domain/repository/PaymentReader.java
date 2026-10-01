package com.grandis.nova.payment.domain.repository;

import com.grandis.nova.payment.domain.model.Payment;
import com.grandis.nova.payment.vo.PaymentTarget;

import java.util.Optional;

/** 성공한 결제 읽기 포트. 대상당 0~1건이다(uq_payment_target). */
public interface PaymentReader {

    Optional<Payment> findPaymentByTarget(PaymentTarget target);
}
