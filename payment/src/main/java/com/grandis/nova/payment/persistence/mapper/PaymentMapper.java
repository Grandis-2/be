package com.grandis.nova.payment.persistence.mapper;

import com.grandis.nova.payment.domain.model.Payment;
import com.grandis.nova.payment.domain.model.PaymentTransaction;
import com.grandis.nova.payment.persistence.entity.PaymentJpaEntity;
import com.grandis.nova.payment.persistence.entity.PaymentTransactionJpaEntity;
import com.grandis.nova.payment.vo.IdempotencyKey;
import com.grandis.nova.payment.vo.LeaseToken;
import com.grandis.nova.payment.vo.Money;
import com.grandis.nova.payment.vo.PaymentTarget;
import com.grandis.nova.payment.vo.ProviderError;
import com.grandis.nova.payment.vo.ProviderOrderId;
import com.grandis.nova.payment.vo.ProviderPaymentKey;

import java.util.function.Function;

/** 도메인 ↔ JPA 엔티티 변환. 엔티티가 persistence 밖으로 새지 않게 어댑터({@code JpaPaymentStore})만 쓴다. */
public final class PaymentMapper {

    private PaymentMapper() {
    }

    /** 새 거래만 엔티티로 만든다 — 리스 · 재시도 · 결과 칸은 조건부 UPDATE 로만 채운다. */
    public static PaymentTransactionJpaEntity toEntity(PaymentTransaction transaction) {
        return new PaymentTransactionJpaEntity(transaction.target().type(), transaction.target().id(), transaction.type(),
                valueOf(transaction.providerOrderId(), ProviderOrderId::value),
                valueOf(transaction.providerPaymentKey(), ProviderPaymentKey::value), transaction.amount().amount(),
                transaction.idempotencyKey().value(), transaction.status(), transaction.attemptCount(),
                transaction.createdAt());
    }

    public static PaymentTransaction toDomain(PaymentTransactionJpaEntity entity) {
        return new PaymentTransaction(entity.getId(), new PaymentTarget(entity.getTargetType(), entity.getTargetId()),
                entity.getTransactionType(),
                valueOf(entity.getProviderOrderId(), ProviderOrderId::new),
                valueOf(entity.getProviderPaymentKey(), ProviderPaymentKey::new), new Money(entity.getAmount()),
                new IdempotencyKey(entity.getIdempotencyKey()), entity.getStatus(), entity.getAttemptCount(),
                entity.getNextRetryAt(), valueOf(entity.getLeaseToken(), LeaseToken::new), entity.getLeaseExpiresAt(),
                entity.getLastErrorCode() == null ? null
                        : new ProviderError(entity.getLastErrorCode(), entity.getLastErrorMessage()),
                entity.getRequestedAt(), entity.getFinishedAt(), entity.getCreatedAt());
    }

    public static PaymentJpaEntity toEntity(Payment payment) {
        return new PaymentJpaEntity(payment.target().type(), payment.target().id(), payment.providerPaymentKey().value(),
                payment.amount().amount(), payment.status(), payment.approvedAt(), payment.refundedAt());
    }

    public static Payment toDomain(PaymentJpaEntity entity) {
        return new Payment(entity.getId(), new PaymentTarget(entity.getTargetType(), entity.getTargetId()),
                new ProviderPaymentKey(entity.getProviderPaymentKey()),
                new Money(entity.getAmount()), entity.getStatus(), entity.getApprovedAt(), entity.getRefundedAt(),
                entity.getCreatedAt(), entity.getUpdatedAt());
    }

    private static <A, B> B valueOf(A value, Function<A, B> convert) {
        return value == null ? null : convert.apply(value);
    }
}
