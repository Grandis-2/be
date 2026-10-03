package com.grandis.nova.payment.domain.repository;

import com.grandis.nova.payment.domain.model.Payment;
import com.grandis.nova.payment.vo.PaymentTarget;
import com.grandis.nova.payment.vo.ProviderPaymentKey;

import java.time.Instant;

/** 성공한 결제 쓰기 포트. {@link com.grandis.nova.payment.PaymentLedger} 만 쓴다(PaymentArchitectureTest). */
public interface PaymentWriter {

    /** 새 결제를 저장하고 id · 생성 시각이 채워진 결제를 돌려준다. */
    Payment insert(Payment payment);

    /**
     * 대상의 결제가 그 결제 키로 SUCCEEDED 일 때만 REFUNDED 로 바꾸고 환불 시각을 적는다.
     * 결제 키까지 조건에 두어, 환불 행이 다른 결제의 키를 들고 있으면 0행이 된다.
     *
     * @return 바뀐 행 수(0 또는 1)
     */
    int markRefunded(PaymentTarget target, ProviderPaymentKey paymentKey, Instant refundedAt, Instant now);
}
