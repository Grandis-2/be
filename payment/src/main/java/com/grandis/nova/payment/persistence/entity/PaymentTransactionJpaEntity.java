package com.grandis.nova.payment.persistence.entity;

import com.grandis.nova.payment.domain.enums.TransactionStatus;
import com.grandis.nova.payment.domain.enums.TransactionType;
import com.grandis.nova.payment.domain.enums.TargetType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * payment_transactions 행. persistence 밖으로 내보내지 않는다.
 *
 * 칼럼은 전부 updatable = false 다. 상태는 조건부 UPDATE(PaymentTransactionJpaRepository)로만 바꾸므로, 이 엔티티를 읽어 둔
 * 트랜잭션이 커밋할 때 변경 감지가 옛 값(특히 리스)을 덮어쓰는 일을 칼럼 수준에서 막아 둔다.
 * updated_at 칼럼이 없어 BaseEntity 를 잇지 않고 id(UUID v7)를 직접 둔다 — created_at 은 원장이 Clock 으로 정해 넘긴다.
 */
@Entity
@Table(name = "payment_transactions")
public class PaymentTransactionJpaEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private TargetType targetType;

    @Column(nullable = false, updatable = false)
    private UUID targetId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 10)
    private TransactionType transactionType;

    @Column(updatable = false, length = 64)
    private String providerOrderId;

    @Column(updatable = false, length = 200)
    private String providerPaymentKey;

    @Column(nullable = false, updatable = false, precision = 12, scale = 0)
    private BigDecimal amount;

    @Column(nullable = false, updatable = false, length = 64)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private TransactionStatus status;

    @Column(nullable = false, updatable = false)
    private int attemptCount;

    @Column(updatable = false)
    private Instant nextRetryAt;

    @Column(updatable = false, length = 64)
    private String leaseToken;

    @Column(updatable = false)
    private Instant leaseExpiresAt;

    @Column(updatable = false, length = 100)
    private String lastErrorCode;

    @Column(updatable = false, length = 500)
    private String lastErrorMessage;

    @Column(updatable = false)
    private Instant requestedAt;

    @Column(updatable = false)
    private Instant finishedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(updatable = false)
    private Instant escalatedAt;

    protected PaymentTransactionJpaEntity() {
    }

    public PaymentTransactionJpaEntity(TargetType targetType, UUID targetId, TransactionType transactionType,
                                       String providerOrderId, String providerPaymentKey, BigDecimal amount, String idempotencyKey,
                                       TransactionStatus status, int attemptCount, Instant createdAt) {
        this.targetType = targetType;
        this.targetId = targetId;
        this.transactionType = transactionType;
        this.providerOrderId = providerOrderId;
        this.providerPaymentKey = providerPaymentKey;
        this.amount = amount;
        this.idempotencyKey = idempotencyKey;
        this.status = status;
        this.attemptCount = attemptCount;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public TargetType getTargetType() {
        return targetType;
    }

    public UUID getTargetId() {
        return targetId;
    }

    public TransactionType getTransactionType() {
        return transactionType;
    }

    public String getProviderOrderId() {
        return providerOrderId;
    }

    public String getProviderPaymentKey() {
        return providerPaymentKey;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public TransactionStatus getStatus() {
        return status;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public Instant getNextRetryAt() {
        return nextRetryAt;
    }

    public String getLeaseToken() {
        return leaseToken;
    }

    public Instant getLeaseExpiresAt() {
        return leaseExpiresAt;
    }

    public String getLastErrorCode() {
        return lastErrorCode;
    }

    public String getLastErrorMessage() {
        return lastErrorMessage;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getEscalatedAt() {
        return escalatedAt;
    }
}
