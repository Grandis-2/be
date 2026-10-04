package com.grandis.nova.payment.outbox;

import com.grandis.nova.payment.domain.model.Payment;
import com.grandis.nova.payment.domain.model.PaymentTransaction;
import com.grandis.nova.payment.vo.Money;
import com.grandis.nova.payment.vo.PaymentTarget;
import com.grandis.nova.payment.vo.ProviderPaymentKey;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 환불 결과 메시지는 완료 = 환불 시각만, 실패 = 시각 없음. 주문 결제의 환불만 이 종류로 보낸다. */
class OrderRefundSettledTest {

    static final Instant NOW = Instant.parse("2026-10-04T00:00:00Z");

    @Test
    void refundedCarriesOrderAmountAndTime() {
        PaymentTransaction refund = refundOf(PaymentTarget.order(9L));

        OrderRefundSettled settled = OrderRefundSettled.refunded(refund, NOW);

        assertThat(settled.aggregateId()).isEqualTo(9L);
        assertThat(settled.eventType()).isEqualTo(OutboundEventType.ORDER_REFUND_SETTLED);
        assertThat(settled.eventType().destination()).isEqualTo("order-events");
        assertThat(settled.result()).isEqualTo(OrderRefundSettled.Result.REFUNDED);
        assertThat(settled.amount()).isEqualByComparingTo("5000");
        assertThat(settled.refundedAt()).isEqualTo(NOW);
    }

    @Test
    void failedHasNoRefundTime() {
        OrderRefundSettled settled = OrderRefundSettled.failed(refundOf(PaymentTarget.order(9L)));

        assertThat(settled.result()).isEqualTo(OrderRefundSettled.Result.FAILED);
        assertThat(settled.refundedAt()).isNull();
    }

    @Test
    void rejectsDrawEntryTarget() {
        PaymentTransaction refund = refundOf(PaymentTarget.drawEntry(9L));

        assertThatThrownBy(() -> OrderRefundSettled.failed(refund)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMismatchedFields() {
        BigDecimal amount = BigDecimal.TEN;
        assertThatThrownBy(() -> new OrderRefundSettled(1L, OrderRefundSettled.Result.REFUNDED, amount, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderRefundSettled(1L, OrderRefundSettled.Result.FAILED, amount, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static PaymentTransaction refundOf(PaymentTarget target) {
        Payment payment = Payment.approved(target, new ProviderPaymentKey("tgen_refund_settled"), Money.won(5000), NOW);
        return PaymentTransaction.openRefund(payment, NOW);
    }
}
