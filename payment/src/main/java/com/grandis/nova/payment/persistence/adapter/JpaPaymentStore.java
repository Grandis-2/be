package com.grandis.nova.payment.persistence.adapter;

import com.grandis.nova.payment.domain.enums.PaymentStatus;
import com.grandis.nova.payment.domain.enums.TransactionStatus;
import com.grandis.nova.payment.domain.enums.TransactionType;
import com.grandis.nova.payment.domain.model.Payment;
import com.grandis.nova.payment.domain.model.PaymentTransaction;
import com.grandis.nova.payment.domain.repository.PaymentReader;
import com.grandis.nova.payment.domain.repository.PaymentTransactionReader;
import com.grandis.nova.payment.domain.repository.PaymentTransactionWriter;
import com.grandis.nova.payment.domain.repository.PaymentWriter;
import com.grandis.nova.payment.persistence.entity.PaymentTransactionJpaEntity;
import com.grandis.nova.payment.persistence.mapper.PaymentMapper;
import com.grandis.nova.payment.persistence.repository.PaymentJpaRepository;
import com.grandis.nova.payment.persistence.repository.PaymentTransactionJpaRepository;
import com.grandis.nova.payment.vo.IdempotencyKey;
import com.grandis.nova.payment.vo.LeaseToken;
import com.grandis.nova.payment.vo.PaymentTarget;
import com.grandis.nova.payment.vo.ProviderError;
import com.grandis.nova.payment.vo.ProviderOrderId;
import com.grandis.nova.payment.vo.ProviderPaymentKey;
import com.grandis.nova.payment.domain.exception.ActiveTransactionExistsException;
import jakarta.persistence.EntityManager;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 결제 읽기 · 쓰기 포트의 JPA 구현. 엔티티는 여기서 만들고 여기서 도메인으로 바꿔 내보낸다.
 *
 * 쓰기마다 바로 flush 한다. 커밋 때 한꺼번에 flush 되면 제약 위반이 부른 자리가 아니라 커밋에서, Spring 예외로
 * 바뀌지 않은 채 올라온다. 여기서 flush 해야 @Repository 예외 변환을 거쳐 부른 자리에서 드러난다.
 * 조건부 UPDATE 뒤에는 그 행의 엔티티만 떼어내 다음 조회가 DB 에서 읽게 한다.
 */
@Repository
class JpaPaymentStore implements PaymentTransactionReader, PaymentTransactionWriter, PaymentReader, PaymentWriter {

    /** 대상당 진행 중 · 성공 거래 1건(유형별). 결제사 호출 전에 이중 청구 · 이중 환불을 막는다. */
    static final String ACTIVE_PER_TARGET = "uq_payment_tx_active";

    private final PaymentTransactionJpaRepository transactions;
    private final PaymentJpaRepository payments;
    private final EntityManager entityManager;

    JpaPaymentStore(PaymentTransactionJpaRepository transactions, PaymentJpaRepository payments,
                    EntityManager entityManager) {
        this.transactions = transactions;
        this.payments = payments;
        this.entityManager = entityManager;
    }

    /** 새 PENDING 거래만 받는다. 다른 상태로 넣으면 상태 머신 · 리스를 거치지 않은 행이 생긴다. */
    @Override
    public PaymentTransaction insert(PaymentTransaction transaction) {
        if (transaction.id() != null || transaction.status() != TransactionStatus.PENDING) {
            throw new IllegalArgumentException("새 PENDING 거래만 저장할 수 있다: " + transaction);
        }
        try {
            return PaymentMapper.toDomain(transactions.saveAndFlush(PaymentMapper.toEntity(transaction)));
        } catch (DataIntegrityViolationException e) {
            throw translateActive(e, transaction);
        }
    }

    @Override
    public int start(PaymentTransaction seen, ProviderPaymentKey paymentKey, LeaseToken lease, Duration leaseFor,
                     Instant now) {
        try {
            return detachAfter(seen.id(), transactions.start(seen.id(), paymentKey.value(), lease.value(),
                    micros(leaseFor), now));
        } catch (DataIntegrityViolationException e) {
            throw translateActive(e, seen);
        }
    }

    @Override
    public int claim(Long transactionId, TransactionStatus seenStatus, LeaseToken seenLease, LeaseToken lease,
                     Duration leaseFor, Instant now) {
        return detachAfter(transactionId, transactions.claim(transactionId, seenStatus.name(),
                seenLease == null ? null : seenLease.value(), lease.value(), micros(leaseFor), now));
    }

    @Override
    public int finish(Long transactionId, LeaseToken lease, TransactionStatus to, ProviderError error, Instant now) {
        if (!to.isFinished()) {
            throw new IllegalArgumentException("끝내는 상태가 아니다: " + to);
        }
        return detachAfter(transactionId, transactions.finish(transactionId, lease.value(), to.name(),
                error == null ? null : error.code(), error == null ? null : error.message(), now));
    }

    @Override
    public int reschedule(Long transactionId, LeaseToken lease, ProviderError error, Duration delay) {
        return detachAfter(transactionId, transactions.reschedule(transactionId, lease.value(), error.code(),
                error.message(), micros(delay)));
    }

    @Override
    public int recordError(Long transactionId, LeaseToken lease, ProviderError error) {
        return detachAfter(transactionId, transactions.recordError(transactionId, lease.value(), error.code(),
                error.message()));
    }

    @Override
    public int expire(Long transactionId, Duration openedFor, Instant now) {
        return detachAfter(transactionId, transactions.expire(transactionId, micros(openedFor), now));
    }

    @Override
    public int reserve(Long transactionId) {
        return detachAfter(transactionId, transactions.reserve(transactionId));
    }

    @Override
    public int escalate(Long transactionId, LeaseToken lease, ProviderError error, Instant now) {
        return detachAfter(transactionId, transactions.escalate(transactionId, lease.value(), error.code(),
                error.message(), now));
    }

    @Override
    public int rotateIdempotencyKey(Long transactionId, LeaseToken lease, IdempotencyKey key, Duration leaseFor) {
        return detachAfter(transactionId, transactions.rotateIdempotencyKey(transactionId, lease.value(), key.value(),
                micros(leaseFor)));
    }

    @Override
    public List<PaymentTransaction> findExpirableCaptures(Duration openedFor, int limit) {
        return transactions.findExpirableCaptures(micros(openedFor), limit).stream()
                .map(PaymentMapper::toDomain).toList();
    }

    @Override
    public Optional<PaymentTransaction> lockNextRecoverable(TransactionType type) {
        Optional<PaymentTransactionJpaEntity> next = transactions.lockNextRetryDue(type.name())
                .or(() -> transactions.lockNextLeaseExpired(type.name()))
                .or(() -> type == TransactionType.REFUND ? transactions.lockNextPendingRefund() : Optional.empty());
        return next.map(PaymentMapper::toDomain);
    }

    @Override
    public Optional<PaymentTransaction> findById(Long transactionId) {
        return transactions.findById(transactionId).map(PaymentMapper::toDomain);
    }

    @Override
    public Optional<PaymentTransaction> findByProviderOrderId(ProviderOrderId providerOrderId) {
        return transactions.findByProviderOrderId(providerOrderId.value()).map(PaymentMapper::toDomain);
    }

    @Override
    public List<PaymentTransaction> findTransactionsByTarget(PaymentTarget target) {
        return transactions.findByTargetTypeAndTargetIdOrderByIdAsc(target.type(), target.id()).stream()
                .map(PaymentMapper::toDomain).toList();
    }

    @Override
    public Payment insert(Payment payment) {
        if (payment.id() != null || payment.status() != PaymentStatus.SUCCEEDED) {
            throw new IllegalArgumentException("새 SUCCEEDED 결제만 저장할 수 있다: " + payment);
        }
        return PaymentMapper.toDomain(payments.saveAndFlush(PaymentMapper.toEntity(payment)));
    }

    @Override
    public int markRefunded(PaymentTarget target, ProviderPaymentKey paymentKey, Instant refundedAt, Instant now) {
        int updated = payments.markRefunded(target.type(), target.id(), paymentKey.value(), refundedAt, now,
                PaymentStatus.SUCCEEDED, PaymentStatus.REFUNDED);
        // 읽어 둔 엔티티가 있으면 조회가 그 관리 중인 인스턴스(옛 상태)를 돌려준다. 그것만 떼어낸다.
        payments.findByTargetTypeAndTargetId(target.type(), target.id()).ifPresent(entityManager::detach);
        return updated;
    }

    @Override
    public Optional<Payment> findPaymentByTarget(PaymentTarget target) {
        return payments.findByTargetTypeAndTargetId(target.type(), target.id()).map(PaymentMapper::toDomain);
    }

    @Override
    public Optional<Payment> lockPaymentByTarget(PaymentTarget target) {
        return payments.lockByTarget(target.type(), target.id()).map(PaymentMapper::toDomain);
    }

    private int detachAfter(Long transactionId, int updated) {
        detach(PaymentTransactionJpaEntity.class, transactionId);
        return updated;
    }

    /** getReference 는 관리 중인 엔티티가 있으면 그것을, 없으면 프록시를 돌려줄 뿐 SELECT 하지 않는다. */
    private void detach(Class<?> type, Long id) {
        entityManager.detach(entityManager.getReference(type, id));
    }

    /** 대상당 진행 중 · 성공 거래 제약이면 도메인 예외로, 아니면 그대로 올린다. */
    private static RuntimeException translateActive(DataIntegrityViolationException e, PaymentTransaction transaction) {
        return violates(e, ACTIVE_PER_TARGET)
                ? new ActiveTransactionExistsException(transaction.target(), transaction.type(), e)
                : e;
    }

    /**
     * 위반한 제약이 그것인가. 메시지가 아니라 Hibernate 가 뽑은 제약 이름으로 판정한다.
     * MySQL 은 UNIQUE 위반에 "테이블.인덱스" 로 알려 오므로 끝부분을 본다.
     */
    private static boolean violates(DataIntegrityViolationException e, String constraint) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ConstraintViolationException violation) {
                String name = violation.getConstraintName();
                return name != null && (name.equals(constraint) || name.endsWith("." + constraint));
            }
        }
        return false;
    }

    private static long micros(Duration duration) {
        return duration.toNanos() / 1_000;
    }
}
