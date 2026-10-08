package com.grandis.nova.payment.support;

import com.grandis.nova.payment.vo.PaymentTarget;
import com.grandis.nova.payment.vo.ProviderPaymentKey;

import java.util.UUID;

/**
 * 통합 테스트는 커밋된 행을 서로 공유한다. 대상(uq_payment_target) · 결제 키(uq_payment_key)는 유일해야 하므로
 * 테스트마다 새로 만든다. 대상 id 는 다른 서비스의 id 라 결제 DB 에 그 행이 없어도 된다(외래 키 없음).
 */
public final class PaymentFixtures {

    private PaymentFixtures() {
    }

    public static PaymentTarget newOrderTarget() {
        return PaymentTarget.order(UUID.randomUUID());
    }

    public static PaymentTarget newDrawEntryTarget() {
        return PaymentTarget.drawEntry(UUID.randomUUID());
    }

    public static ProviderPaymentKey newProviderPayment() {
        return new ProviderPaymentKey("tgen_" + UUID.randomUUID());
    }
}
