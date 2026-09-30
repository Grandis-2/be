package com.grandis.nova.payment.persistence.entity;

import com.grandis.nova.common.BaseEntity;
import com.grandis.nova.payment.domain.enums.PaymentStatus;
import com.grandis.nova.payment.domain.enums.TargetType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * payments 행. persistence 밖으로 내보내지 않는다. 칼럼은 전부 updatable = false 다 — 환불 표시는 조건부 UPDATE
 * (PaymentJpaRepository.markRefunded)로만 한다.
 */
@Entity
@Table(name = "payments")
public class PaymentJpaEntity extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private TargetType targetType;

    @Column(nullable = false, updatable = false)
    private Long targetId;

    @Column(nullable = false, updatable = false, length = 200)
    private String providerPaymentKey;

    @Column(nullable = false, updatable = false, precision = 12, scale = 0)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 10)
    private PaymentStatus status;

    @Column(nullable = false, updatable = false)
    private Instant approvedAt;

    @Column(updatable = false)
    private Instant refundedAt;

    protected PaymentJpaEntity() {
    }

    public PaymentJpaEntity(TargetType targetType, Long targetId, String providerPaymentKey, BigDecimal amount, PaymentStatus status,
                            Instant approvedAt, Instant refundedAt) {
        this.targetType = targetType;
        this.targetId = targetId;
        this.providerPaymentKey = providerPaymentKey;
        this.amount = amount;
        this.status = status;
        this.approvedAt = approvedAt;
        this.refundedAt = refundedAt;
    }

    public Long getId() {
        return id;
    }

    public TargetType getTargetType() {
        return targetType;
    }

    public Long getTargetId() {
        return targetId;
    }

    public String getProviderPaymentKey() {
        return providerPaymentKey;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }

    public Instant getRefundedAt() {
        return refundedAt;
    }
}
