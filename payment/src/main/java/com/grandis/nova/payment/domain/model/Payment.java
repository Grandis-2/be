package com.grandis.nova.payment.domain.model;

import com.grandis.nova.payment.domain.enums.PaymentStatus;
import com.grandis.nova.payment.vo.Money;
import com.grandis.nova.payment.vo.PaymentTarget;
import com.grandis.nova.payment.vo.ProviderPaymentKey;

import java.time.Instant;
import java.util.Objects;

/**
 * 성공한 결제(payments). 대상당 1행이고 CAPTURE 성공 반영과 같은 트랜잭션에서 생긴다. 시도 · 재시도는
 * {@link PaymentTransaction} 에 있다. 불변이다 — 환불 표시는 저장소의 조건부 UPDATE(SUCCEEDED → REFUNDED)로 한다.
 *
 * @param id        저장 전이면 null
 * @param createdAt 저장 전이면 null
 * @param updatedAt 저장 전이면 null
 */
public record Payment(
        Long id,
        PaymentTarget target,
        ProviderPaymentKey providerPaymentKey,
        Money amount,
        PaymentStatus status,
        Instant approvedAt,
        Instant refundedAt,
        Instant createdAt,
        Instant updatedAt
) {

    public Payment {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(providerPaymentKey, "providerPaymentKey");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(approvedAt, "approvedAt");
        // ck_payment_refunded_at
        if ((status == PaymentStatus.REFUNDED) != (refundedAt != null)) {
            throw new IllegalArgumentException("환불된 결제만 환불 시각이 있다: status=" + status);
        }
    }

    public static Payment approved(PaymentTarget target, ProviderPaymentKey paymentKey, Money amount,
                                   Instant approvedAt) {
        return new Payment(null, target, paymentKey, amount, PaymentStatus.SUCCEEDED, approvedAt, null, null, null);
    }

    @Override
    public String toString() {
        return "Payment[id=%s, target=%s, status=%s]".formatted(id, target, status);
    }
}
