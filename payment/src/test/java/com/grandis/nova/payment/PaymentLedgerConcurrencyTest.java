package com.grandis.nova.payment;

import com.grandis.nova.common.UuidBinary;
import com.grandis.nova.common.testing.Concurrently;
import com.grandis.nova.common.testing.Concurrently.Outcome;
import com.grandis.nova.payment.domain.enums.TransactionStatus;
import com.grandis.nova.payment.domain.enums.TransactionType;
import com.grandis.nova.payment.domain.exception.ActiveTransactionExistsException;
import com.grandis.nova.payment.domain.model.Outcome.Confirmed;
import com.grandis.nova.payment.domain.model.PaymentTransaction;
import com.grandis.nova.payment.domain.repository.PaymentTransactionReader;
import com.grandis.nova.payment.support.PaymentFixtures;
import com.grandis.nova.payment.support.PaymentIntegrationTest;
import com.grandis.nova.payment.vo.Money;
import com.grandis.nova.payment.vo.PaymentTarget;
import com.grandis.nova.payment.vo.ProviderPaymentKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 같은 스냅샷을 본 작업자 여럿이 동시에 선점한다. 각자 트랜잭션을 열고 커밋한다. 한 명만 리스를 쥔다. */
@PaymentIntegrationTest
class PaymentLedgerConcurrencyTest {

    static final int WORKERS = 8;
    static final Money AMOUNT = Money.won(1_250_000);

    @Autowired
    PaymentLedger ledger;

    @Autowired
    PaymentTransactionReader transactions;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    PaymentTarget target;
    ProviderPaymentKey providerPayment;

    @BeforeEach
    void setUp() {
        target = PaymentFixtures.newOrderTarget();
        providerPayment = PaymentFixtures.newProviderPayment();
    }

    @Test
    void concurrentStartsOfSameCaptureLeaveOneLeaseHolder() throws Exception {
        PaymentTransaction pending = transactionTemplate.execute(s -> ledger.openCapture(target, AMOUNT));

        List<Outcome<Optional<ClaimedTransaction>>> outcomes = Concurrently.run(WORKERS, i -> () ->
                transactionTemplate.execute(s -> ledger.start(pending, target, providerPayment, AMOUNT)));

        assertOneHolder(outcomes, pending.id());
    }

    @Test
    void concurrentClaimsOfExpiredLeaseLeaveOneLeaseHolder() throws Exception {
        PaymentTransaction pending = transactionTemplate.execute(s -> ledger.openCapture(target, AMOUNT));
        PaymentTransaction started = transactionTemplate.execute(s -> ledger.start(pending, target, providerPayment, AMOUNT))
                .orElseThrow().transaction();
        jdbcTemplate.update("UPDATE payment_transactions SET lease_expires_at = UTC_TIMESTAMP(6) - INTERVAL 1 SECOND "
                + "WHERE id = ?", UuidBinary.toBytes(started.id()));

        List<Outcome<Optional<ClaimedTransaction>>> outcomes = Concurrently.run(WORKERS, i -> () ->
                transactionTemplate.execute(s -> ledger.claim(started)));

        assertOneHolder(outcomes, started.id());
        assertThat(transactions.findById(started.id()).orElseThrow().attemptCount()).isEqualTo(2);
    }

    // 같은 대상의 서로 다른 CAPTURE 둘을 동시에 시작한다. 행이 달라 행 조건으로는 둘 다 1행이다 — 대상 단위 유일성이 막는다.
    @Test
    void concurrentStartsOfTwoCapturesOfSameTargetLeaveOneInFlight() throws Exception {
        List<PaymentTransaction> pendings = List.of(
                transactionTemplate.execute(s -> ledger.openCapture(target, AMOUNT)),
                transactionTemplate.execute(s -> ledger.openCapture(target, AMOUNT)));

        List<Outcome<Optional<ClaimedTransaction>>> outcomes = Concurrently.run(pendings.size(), i -> () ->
                transactionTemplate.execute(s -> ledger.start(pendings.get(i), target,
                        PaymentFixtures.newProviderPayment(), AMOUNT)));

        assertThat(outcomes.stream().filter(o -> o.succeeded() && o.value().isPresent())).hasSize(1);
        assertThat(outcomes.stream().filter(o -> !o.succeeded()))
                .singleElement().satisfies(o -> assertThat(o.error()).isInstanceOf(ActiveTransactionExistsException.class));
        assertThat(transactions.findTransactionsByTarget(target)).extracting(PaymentTransaction::status)
                .containsExactlyInAnyOrder(TransactionStatus.PROCESSING, TransactionStatus.PENDING);
    }

    /*
     * 같은 대상의 환불 요청이 동시에 온다(환불 요청은 최소 1회 전달이라 재전송이 겹칠 수 있다). 모두 결제를 읽고 "아직 환불 안 됨" 을
     * 본 뒤 INSERT 하므로 읽기로는 막지 못한다 — uq_payment_tx_active 가 REFUND 행을 하나만 남긴다.
     * 먼저 넣은 쪽이 커밋하므로 기다리던 쪽은 모두 중복 키(교착 아님)를 받는다.
     */
    @Test
    void concurrentRefundOpensOfSameTargetLeaveOneRefund() throws Exception {
        PaymentTransaction pending = transactionTemplate.execute(s -> ledger.openCapture(target, AMOUNT));
        ClaimedTransaction started = transactionTemplate.execute(s -> ledger.start(pending, target, providerPayment,
                AMOUNT)).orElseThrow();
        transactionTemplate.executeWithoutResult(s -> ledger.resolve(started, new Confirmed(Instant.now())));

        List<Outcome<PaymentTransaction>> outcomes = Concurrently.run(WORKERS, i -> () ->
                transactionTemplate.execute(s -> ledger.openRefund(target)));

        assertThat(outcomes.stream().filter(Outcome::succeeded)).hasSize(1);
        assertThat(outcomes.stream().filter(o -> !o.succeeded())).hasSize(WORKERS - 1)
                .allSatisfy(o -> assertThat(o.error()).isInstanceOf(ActiveTransactionExistsException.class));
        assertThat(transactions.findTransactionsByTarget(target)).filteredOn(t -> t.type() == TransactionType.REFUND)
                .hasSize(1);
    }

    private void assertOneHolder(List<Outcome<Optional<ClaimedTransaction>>> outcomes, UUID id) {
        assertThat(outcomes).allSatisfy(o -> assertThat(o.error()).isNull());
        List<ClaimedTransaction> holders = outcomes.stream().map(Outcome::value).flatMap(Optional::stream).toList();
        assertThat(holders).hasSize(1);
        assertThat(transactions.findById(id).orElseThrow().leaseToken())
                .isEqualTo(holders.getFirst().transaction().leaseToken());
    }
}
