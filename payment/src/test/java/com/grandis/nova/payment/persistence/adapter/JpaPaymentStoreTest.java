package com.grandis.nova.payment.persistence.adapter;

import com.grandis.nova.payment.domain.model.Payment;
import com.grandis.nova.payment.domain.model.PaymentTransaction;
import com.grandis.nova.payment.domain.repository.PaymentReader;
import com.grandis.nova.payment.domain.repository.PaymentTransactionReader;
import com.grandis.nova.payment.domain.repository.PaymentTransactionWriter;
import com.grandis.nova.payment.domain.repository.PaymentWriter;
import com.grandis.nova.payment.vo.ProviderPaymentKey;
import com.grandis.nova.payment.support.PaymentFixtures;
import com.grandis.nova.payment.support.PaymentIntegrationTest;
import com.grandis.nova.payment.vo.Money;
import com.grandis.nova.payment.vo.PaymentTarget;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 도메인 → 행 → 도메인 왕복. 영속성 컨텍스트를 비운 뒤 읽어야 DB 행에서 엔티티를 만드는 경로를 탄다.
 */
@PaymentIntegrationTest
class JpaPaymentStoreTest {

    static final Instant NOW = Instant.parse("2026-09-29T01:02:03.456789Z");
    static final Money AMOUNT = Money.won(1_250_000);

    @Autowired
    PaymentTransactionReader transactionReader;

    @Autowired
    PaymentTransactionWriter transactionWriter;

    @Autowired
    PaymentReader paymentReader;

    @Autowired
    PaymentWriter paymentWriter;

    @Autowired
    EntityManager entityManager;

    @Autowired
    TransactionTemplate transactionTemplate;

    PaymentTarget target;
    ProviderPaymentKey providerPayment;

    @BeforeEach
    void setUp() {
        providerPayment = PaymentFixtures.newProviderPayment();
        target = PaymentFixtures.newOrderTarget();
    }

    @Test
    void storedTransactionsReadBackUnchanged() {
        PaymentTransaction capture = PaymentTransaction.openCapture(target, AMOUNT, NOW);
        PaymentTransaction refund = PaymentTransaction.openRefund(Payment.approved(target, providerPayment, AMOUNT, NOW), NOW);

        transactionTemplate.executeWithoutResult(s -> {
            UUID captureId = transactionWriter.insert(capture).id();
            UUID refundId = transactionWriter.insert(refund).id();
            entityManager.clear();

            assertThat(transactionReader.findById(captureId).orElseThrow()).usingRecursiveComparison()
                    .ignoringFields("id").isEqualTo(capture);
            assertThat(transactionReader.findById(refundId).orElseThrow()).usingRecursiveComparison()
                    .ignoringFields("id").isEqualTo(refund);
        });
    }

    @Test
    void storedPaymentReadsBackUnchanged() {
        Payment approved = Payment.approved(target, providerPayment, AMOUNT, NOW);

        transactionTemplate.executeWithoutResult(s -> {
            paymentWriter.insert(approved);
            entityManager.clear();

            assertThat(paymentReader.findPaymentByTarget(target).orElseThrow()).usingRecursiveComparison()
                    .ignoringFields("id", "createdAt", "updatedAt").isEqualTo(approved);
        });
    }

    @Test
    void onlyNewPendingTransactionsAreInserted() {
        PaymentTransaction stored = transactionTemplate.execute(s ->
                transactionWriter.insert(PaymentTransaction.openCapture(target, AMOUNT, NOW)));

        assertThatThrownBy(() -> transactionTemplate.execute(s -> transactionWriter.insert(stored)))
                .isInstanceOf(InvalidDataAccessApiUsageException.class);
    }

    // 벌크 UPDATE 뒤 같은 트랜잭션의 조회가 컨텍스트의 옛 엔티티가 아니라 DB 의 새 상태를 읽는다.
    @Test
    void refundMarkIsVisibleInSameTransaction() {
        transactionTemplate.executeWithoutResult(s -> {
            paymentWriter.insert(Payment.approved(target, providerPayment, AMOUNT, NOW));
            paymentReader.findPaymentByTarget(target).orElseThrow();

            assertThat(paymentWriter.markRefunded(target, providerPayment, NOW.plusSeconds(1), NOW)).isEqualTo(1);
            assertThat(paymentReader.findPaymentByTarget(target).orElseThrow().refundedAt()).isEqualTo(NOW.plusSeconds(1));
            assertThat(paymentWriter.markRefunded(target, providerPayment, NOW.plusSeconds(2), NOW)).as("두 번 환불 표시 안 됨").isZero();
        });
    }
}
