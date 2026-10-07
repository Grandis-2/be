package com.grandis.nova.payment.persistence.repository;

import com.grandis.nova.payment.domain.enums.PaymentStatus;
import com.grandis.nova.payment.domain.enums.TargetType;
import com.grandis.nova.payment.persistence.entity.PaymentJpaEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface PaymentJpaRepository extends JpaRepository<PaymentJpaEntity, UUID> {

    Optional<PaymentJpaEntity> findByTargetTypeAndTargetId(TargetType targetType, UUID targetId);

    /** 대상의 결제 행을 잠가 읽는다(SELECT … FOR UPDATE). 환불 열기 · 환불 결과 반영을 대상 단위로 줄 세운다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PaymentJpaEntity p where p.targetType = :targetType and p.targetId = :targetId")
    Optional<PaymentJpaEntity> lockByTarget(@Param("targetType") TargetType targetType, @Param("targetId") UUID targetId);

    @Modifying(flushAutomatically = true)
    @Query("""
            update PaymentJpaEntity p
               set p.status = :refunded, p.refundedAt = :refundedAt, p.updatedAt = :now
             where p.targetType = :targetType and p.targetId = :targetId
               and p.providerPaymentKey = :paymentKey and p.status = :succeeded
            """)
    int markRefunded(@Param("targetType") TargetType targetType, @Param("targetId") UUID targetId,
                     @Param("paymentKey") String paymentKey, @Param("refundedAt") Instant refundedAt,
                     @Param("now") Instant now, @Param("succeeded") PaymentStatus succeeded,
                     @Param("refunded") PaymentStatus refunded);
}
