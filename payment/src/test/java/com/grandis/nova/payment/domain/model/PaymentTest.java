package com.grandis.nova.payment.domain.model;

import com.grandis.nova.payment.domain.enums.PaymentStatus;
import com.grandis.nova.payment.vo.Money;
import com.grandis.nova.payment.vo.PaymentTarget;
import com.grandis.nova.payment.vo.ProviderPaymentKey;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentTest {

    static final Instant NOW = Instant.parse("2026-09-29T01:00:00Z");
    static final PaymentTarget TARGET = PaymentTarget.drawEntry(7L);
    static final ProviderPaymentKey PAYMENT = new ProviderPaymentKey("tgen_payment_1");

    @Test
    void approvedPaymentIsSucceededWithoutRefundTime() {
        Payment payment = Payment.approved(TARGET, PAYMENT, Money.won(1000), NOW);

        assertThat(payment.status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(payment.refundedAt()).isNull();
        assertThat(payment.approvedAt()).isEqualTo(NOW);
    }

    @Test
    void refundedAndOnlyRefundedHasRefundTime() { // ck_payment_refunded_at
        assertThatThrownBy(() -> new Payment(1L, TARGET, PAYMENT, Money.won(1000), PaymentStatus.REFUNDED, NOW, null,
                null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Payment(1L, TARGET, PAYMENT, Money.won(1000), PaymentStatus.SUCCEEDED, NOW, NOW,
                null, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toStringHidesPaymentKey() {
        assertThat(Payment.approved(TARGET, PAYMENT, Money.won(1000), NOW).toString()).doesNotContain("tgen_payment_1");
    }
}
