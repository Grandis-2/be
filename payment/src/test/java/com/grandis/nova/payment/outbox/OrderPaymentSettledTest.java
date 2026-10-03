package com.grandis.nova.payment.outbox;

import com.grandis.nova.payment.domain.enums.DeclineReason;
import com.grandis.nova.payment.domain.model.PaymentTransaction;
import com.grandis.nova.payment.vo.Money;
import com.grandis.nova.payment.vo.PaymentTarget;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 결과 메시지는 승인 = 승인 시각만, 거절 = 사유만. 주문 결제만 이 종류로 보낸다. */
class OrderPaymentSettledTest {

    static final Instant NOW = Instant.parse("2026-10-02T00:00:00Z");

    @Test
    void approvedCarriesOrderAndAttempt() {
        PaymentTransaction attempt = PaymentTransaction.openCapture(PaymentTarget.order(9L), Money.won(5000), NOW);

        OrderPaymentSettled settled = OrderPaymentSettled.approved(attempt, NOW);

        assertThat(settled.aggregateId()).isEqualTo(9L);
        assertThat(settled.providerOrderId()).isEqualTo(attempt.providerOrderId().value());
        assertThat(settled.amount()).isEqualByComparingTo("5000");
        assertThat(settled.declineReason()).isNull();
    }

    @Test
    void rejectsDrawEntryTarget() {
        PaymentTransaction entry = PaymentTransaction.openCapture(PaymentTarget.drawEntry(9L), Money.won(1000), NOW);

        assertThatThrownBy(() -> OrderPaymentSettled.approved(entry, NOW)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMismatchedFields() {
        BigDecimal amount = BigDecimal.TEN;
        assertThatThrownBy(() -> new OrderPaymentSettled(1L, "attempt-1", OrderPaymentSettled.Result.APPROVED, amount,
                null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderPaymentSettled(1L, "attempt-1", OrderPaymentSettled.Result.DECLINED, amount,
                NOW, DeclineReason.FAILED)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderPaymentSettled(1L, " ", OrderPaymentSettled.Result.DECLINED, amount,
                null, DeclineReason.FAILED)).isInstanceOf(IllegalArgumentException.class);
    }
}
