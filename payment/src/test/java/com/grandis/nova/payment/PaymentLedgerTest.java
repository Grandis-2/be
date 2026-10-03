package com.grandis.nova.payment;

import com.grandis.nova.payment.domain.enums.PaymentStatus;
import com.grandis.nova.payment.domain.enums.TransactionStatus;
import com.grandis.nova.payment.domain.enums.TransactionType;
import com.grandis.nova.payment.domain.exception.ActiveTransactionExistsException;
import com.grandis.nova.payment.domain.exception.LeaseLostException;
import com.grandis.nova.payment.domain.exception.PaymentAlreadyRefundedException;
import com.grandis.nova.payment.domain.exception.PaymentAmountMismatchException;
import com.grandis.nova.payment.domain.exception.PaymentTargetMismatchException;
import com.grandis.nova.payment.domain.model.Outcome;
import com.grandis.nova.payment.domain.model.Payment;
import com.grandis.nova.payment.domain.model.PaymentTransaction;
import com.grandis.nova.payment.domain.repository.PaymentReader;
import com.grandis.nova.payment.domain.repository.PaymentTransactionReader;
import com.grandis.nova.payment.support.PaymentFixtures;
import com.grandis.nova.payment.support.PaymentIntegrationTest;
import com.grandis.nova.payment.vo.Money;
import com.grandis.nova.payment.vo.PaymentTarget;
import com.grandis.nova.payment.vo.ProviderError;
import com.grandis.nova.payment.vo.ProviderPaymentKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 원장 → 조건부 UPDATE → MySQL. 단계마다 트랜잭션을 따로 열고 커밋한다 — 선점과 반영은 운영에서도 다른 트랜잭션이고,
 * 리스 판정은 DB 시각으로 한다. 리스 만료 · 재시도 시각 도래는 70초를 기다리지 않고 행의 시각을 DB 시각 기준 과거로 당겨 만든다.
 */
@PaymentIntegrationTest
class PaymentLedgerTest {

    static final Money AMOUNT = Money.won(1_250_000);
    static final ProviderError BUSY = new ProviderError("IDEMPOTENT_REQUEST_PROCESSING", "처리 중");
    static final ProviderError TIMEOUT = new ProviderError("READ_TIMEOUT", null);
    static final ProviderError REJECT = new ProviderError("REJECT_CARD_PAYMENT", "한도 초과");
    static final Instant APPROVED_AT = Instant.parse("2026-09-29T02:00:00.123456Z");
    static final Duration OPENED_FOR = Duration.ofMinutes(35);

    @Autowired
    PaymentLedger ledger;

    @Autowired
    PaymentTransactionReader transactions;

    @Autowired
    PaymentReader payments;

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
    void ledgerRefusesToRunOutsideCallersTransaction() {
        assertThatThrownBy(() -> ledger.openCapture(target, AMOUNT))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    // ---- 시작 ----

    @Test
    void startMovesPendingCaptureToProcessingWithLeaseFromDatabaseClock() {
        PaymentTransaction pending = inTx(() -> ledger.openCapture(target, AMOUNT));

        PaymentTransaction started = inTx(() -> ledger.start(pending, target, providerPayment, AMOUNT)).orElseThrow()
                .transaction();

        assertThat(started.status()).isEqualTo(TransactionStatus.PROCESSING);
        assertThat(started.providerPaymentKey()).isEqualTo(providerPayment);
        assertThat(started.attemptCount()).isEqualTo(1);
        assertThat(started.requestedAt()).isNotNull();
        assertThat(started.leaseToken()).isNotNull();
        assertThat(leaseSecondsLeft(started.id())).isBetween(PaymentTransaction.LEASE.toSeconds() - 5,
                PaymentTransaction.LEASE.toSeconds());
    }

    @Test
    void secondStartFromSameSnapshotUpdatesNothing() {
        PaymentTransaction pending = inTx(() -> ledger.openCapture(target, AMOUNT));
        ClaimedTransaction first = inTx(() -> ledger.start(pending, target, providerPayment, AMOUNT)).orElseThrow();

        Optional<ClaimedTransaction> second = inTx(() -> ledger.start(pending, target, providerPayment, AMOUNT));

        assertThat(second).isEmpty();
        assertThat(transactions.findById(pending.id()).orElseThrow().leaseToken())
                .isEqualTo(first.transaction().leaseToken());
    }

    @Test
    void startWithForgedAmountChangesNothing() {
        PaymentTransaction pending = inTx(() -> ledger.openCapture(target, AMOUNT));

        assertThatThrownBy(() -> inTx(() -> ledger.start(pending, target, providerPayment, Money.won(100))))
                .isInstanceOf(PaymentAmountMismatchException.class);
        assertThat(transactions.findById(pending.id()).orElseThrow().status()).isEqualTo(TransactionStatus.PENDING);
    }

    @Test
    void workerDoesNotClaimPendingCaptureEvenIfDomainIsBypassed() {
        PaymentTransaction pending = inTx(() -> ledger.openCapture(target, AMOUNT));

        assertThat(inTx(() -> ledger.claim(pending))).isEmpty();
        assertThat(jdbcTemplate.update("""
                UPDATE payment_transactions SET status = 'PROCESSING'
                 WHERE id = ? AND status = 'PENDING' AND transaction_type = 'REFUND'
                """, pending.id())).isZero();
    }

    // ---- 반영 ----

    @Test
    void confirmedCaptureSucceedsAndRecordsPaymentForTarget() {
        ClaimedTransaction started = started();

        inTxRun(() -> ledger.resolve(started, new Outcome.Confirmed(APPROVED_AT)));

        PaymentTransaction done = transactions.findById(id(started)).orElseThrow();
        assertThat(done.status()).isEqualTo(TransactionStatus.SUCCEEDED);
        assertThat(done.leaseToken()).isNull();
        assertThat(done.finishedAt()).isNotNull();
        Payment payment = payments.findPaymentByTarget(target).orElseThrow();
        assertThat(payment.status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(payment.providerPaymentKey()).isEqualTo(providerPayment);
        assertThat(payment.amount()).isEqualTo(AMOUNT);
        assertThat(payment.approvedAt()).isEqualTo(APPROVED_AT);
    }

    @Test
    void drawEntryIsPaidLikeAnyOtherTarget() {
        target = PaymentFixtures.newDrawEntryTarget();
        ClaimedTransaction started = started();

        inTxRun(() -> ledger.resolve(started, new Outcome.Confirmed(APPROVED_AT)));

        assertThat(payments.findPaymentByTarget(target)).isPresent();
        assertThat(transactions.findTransactionsByTarget(target)).extracting(PaymentTransaction::status)
                .containsExactly(TransactionStatus.SUCCEEDED);
    }

    @Test
    void rejectedCaptureFailsWithErrorAndNoPayment() {
        ClaimedTransaction started = started();

        inTxRun(() -> ledger.resolve(started, new Outcome.Rejected(REJECT)));

        PaymentTransaction done = transactions.findById(id(started)).orElseThrow();
        assertThat(done.status()).isEqualTo(TransactionStatus.FAILED);
        assertThat(done.lastError()).isEqualTo(REJECT);
        assertThat(payments.findPaymentByTarget(target)).isEmpty();
    }

    @Test
    void inProgressReschedulesAtDatabaseTimePlusDelayAndReleasesLease() {
        ClaimedTransaction started = started();

        inTxRun(() -> ledger.resolve(started, new Outcome.InProgress(BUSY, Duration.ofSeconds(300))));

        PaymentTransaction scheduled = transactions.findById(id(started)).orElseThrow();
        assertThat(scheduled.status()).isEqualTo(TransactionStatus.RETRY_SCHEDULED);
        assertThat(scheduled.leaseToken()).isNull();
        assertThat(scheduled.lastError()).isEqualTo(BUSY);
        assertThat(secondsUntilRetry(id(started))).isBetween(295L, 300L);
    }

    @Test
    void unknownKeepsProcessingAndLeaseSoRecoveryResendsAfterExpiry() {
        ClaimedTransaction started = started();

        inTxRun(() -> ledger.resolve(started, new Outcome.Unknown(TIMEOUT)));

        PaymentTransaction still = transactions.findById(id(started)).orElseThrow();
        assertThat(still.status()).isEqualTo(TransactionStatus.PROCESSING);
        assertThat(still.leaseToken()).isEqualTo(started.transaction().leaseToken());
        assertThat(still.leaseExpiresAt()).isEqualTo(started.transaction().leaseExpiresAt());
        assertThat(still.lastError()).isEqualTo(TIMEOUT);
    }

    // 앞선 시도의 오류가 성공한 거래에 남으면 "마지막 시도 결과" 가 실패로 읽힌다.
    @Test
    void successClearsEarlierError() {
        ClaimedTransaction started = started();
        inTxRun(() -> ledger.resolve(started, new Outcome.Unknown(TIMEOUT)));

        inTxRun(() -> ledger.resolve(started, new Outcome.Confirmed(APPROVED_AT)));

        assertThat(transactions.findById(id(started)).orElseThrow().lastError()).isNull();
    }

    // 리스가 만료되면 아무도 다시 선점하지 않았어도 늦은 결과를 받지 않는다. 결과는 같은 멱등 키 재전송이 다시 준다.
    @Test
    void lateResolveAfterLeaseExpiryThrowsForEveryOutcomeAndChangesNothing() {
        ClaimedTransaction started = started();
        expireLease(id(started));

        for (Outcome outcome : List.of(new Outcome.Confirmed(APPROVED_AT), new Outcome.Rejected(REJECT),
                new Outcome.InProgress(BUSY, Duration.ofSeconds(1)), new Outcome.Unknown(TIMEOUT))) {
            assertThatThrownBy(() -> inTxRun(() -> ledger.resolve(started, outcome)))
                    .as(outcome.toString()).isInstanceOf(LeaseLostException.class);
        }

        PaymentTransaction untouched = transactions.findById(id(started)).orElseThrow();
        assertThat(untouched.status()).isEqualTo(TransactionStatus.PROCESSING);
        assertThat(untouched.lastError()).isNull();
        assertThat(payments.findPaymentByTarget(target)).isEmpty();
    }

    /*
     * 리스를 잃은 반영은 같은 트랜잭션의 앞선 업무 변경도 되돌린다. 반환값(boolean)으로 알리면 호출자가 무시하고 커밋할 수 있었고,
     * 그러면 거래는 PROCESSING 인데 업무 변경(아웃박스 기록 · 대상 쪽 알림)만 남는다. 앞선 변경은 같은 트랜잭션에서 연 다른
     * 대상의 거래 행으로 흉내 낸다.
     */
    @Test
    void lostLeaseRollsBackEarlierChangesInSameTransaction() {
        ClaimedTransaction started = started();
        expireLease(id(started));
        PaymentTarget other = PaymentFixtures.newOrderTarget();

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            ledger.openCapture(other, AMOUNT);
            ledger.resolve(started, new Outcome.Confirmed(APPROVED_AT));
        })).isInstanceOf(LeaseLostException.class);

        assertThat(transactions.findTransactionsByTarget(other)).isEmpty();
        assertThat(payments.findPaymentByTarget(target)).isEmpty();
    }

    // ---- 선점 ----

    @Test
    void expiredProcessingIsReclaimedWithNewLeaseAndOldHolderCanNoLongerResolve() {
        ClaimedTransaction started = started();
        PaymentTransaction seen = started.transaction();
        assertThat(inTx(() -> ledger.claim(seen))).as("리스가 살아 있으면 선점하지 않는다").isEmpty();
        expireLease(seen.id());

        ClaimedTransaction reclaimed = inTx(() -> ledger.claim(seen)).orElseThrow();

        assertThat(reclaimed.transaction().leaseToken()).isNotEqualTo(seen.leaseToken());
        assertThat(reclaimed.transaction().attemptCount()).isEqualTo(2);
        assertThat(reclaimed.transaction().idempotencyKey()).as("재전송은 그 행의 멱등 키")
                .isEqualTo(seen.idempotencyKey());
        assertThat(reclaimed.transaction().requestedAt()).as("처음 보낸 시각은 그대로").isEqualTo(seen.requestedAt());
        assertThatThrownBy(() -> inTxRun(() -> ledger.resolve(started, new Outcome.Confirmed(APPROVED_AT))))
                .isInstanceOf(LeaseLostException.class);
        inTxRun(() -> ledger.resolve(reclaimed, new Outcome.Confirmed(APPROVED_AT)));
        assertThat(payments.findPaymentByTarget(target)).isPresent();
    }

    @Test
    void retryScheduledIsClaimedOnlyWhenDue() {
        ClaimedTransaction started = started();
        inTxRun(() -> ledger.resolve(started, new Outcome.InProgress(BUSY, Duration.ofSeconds(300))));
        PaymentTransaction scheduled = transactions.findById(id(started)).orElseThrow();

        assertThat(inTx(() -> ledger.claim(scheduled))).isEmpty();
        makeRetryDue(id(started));
        PaymentTransaction claimed = inTx(() -> ledger.claim(scheduled)).orElseThrow().transaction();

        assertThat(claimed.status()).isEqualTo(TransactionStatus.PROCESSING);
        assertThat(claimed.nextRetryAt()).isNull();
        assertThat(claimed.attemptCount()).isEqualTo(2);
    }

    @Test
    void claimFromStaleSnapshotUpdatesNothing() {
        PaymentTransaction seen = started().transaction();
        expireLease(seen.id());
        inTx(() -> ledger.claim(seen)).orElseThrow();
        expireLease(seen.id());

        assertThat(inTx(() -> ledger.claim(seen))).as("읽은 리스 표식이 바뀌었다").isEmpty();
    }

    // ---- 환불 ----

    @Test
    void confirmedRefundMarksThatPaymentRefunded() {
        inTxRun(() -> ledger.resolve(started(), new Outcome.Confirmed(APPROVED_AT)));
        PaymentTransaction refund = inTx(() -> ledger.openRefund(target));

        ClaimedTransaction claimed = inTx(() -> ledger.claim(refund)).orElseThrow();
        Instant refundedAt = APPROVED_AT.plusSeconds(3600);
        inTxRun(() -> ledger.resolve(claimed, new Outcome.Confirmed(refundedAt)));

        Payment payment = payments.findPaymentByTarget(target).orElseThrow();
        assertThat(payment.status()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(payment.refundedAt()).isEqualTo(refundedAt);
        assertThat(transactions.findById(refund.id()).orElseThrow().status()).isEqualTo(TransactionStatus.SUCCEEDED);
    }

    // 환불은 원장이 대상의 결제를 직접 읽어 연다. 호출자가 결제 스냅샷을 넘기면 조작된 키 · 금액으로 열 수 있다.
    @Test
    void refundIsOpenedFromTheStoredPaymentOfTarget() {
        inTxRun(() -> ledger.resolve(started(), new Outcome.Confirmed(APPROVED_AT)));

        PaymentTransaction refund = inTx(() -> ledger.openRefund(target));

        assertThat(refund.providerPaymentKey()).isEqualTo(providerPayment);
        assertThat(refund.amount()).isEqualTo(AMOUNT);
        assertThat(refund.target()).isEqualTo(target);
    }

    @Test
    void refundWithoutSucceededPaymentIsRefused() {
        assertThatThrownBy(() -> inTx(() -> ledger.openRefund(target))).isInstanceOf(IllegalStateException.class);
    }

    // 환불 요청은 최소 1회 전달이라 재전송이 흔하다. "이미 환불됨" 을 "결제 없음" 과 구분해야 재전송을 성공으로 처리할 수 있다.
    @Test
    void refundOfAlreadyRefundedPaymentIsReportedAsSuch() {
        inTxRun(() -> ledger.resolve(started(), new Outcome.Confirmed(APPROVED_AT)));
        PaymentTransaction refund = inTx(() -> ledger.openRefund(target));
        ClaimedTransaction claimed = inTx(() -> ledger.claim(refund)).orElseThrow();
        inTxRun(() -> ledger.resolve(claimed, new Outcome.Confirmed(APPROVED_AT.plusSeconds(60))));

        assertThatThrownBy(() -> inTx(() -> ledger.openRefund(target)))
                .isInstanceOf(PaymentAlreadyRefundedException.class);
    }

    @Test
    void secondRefundOfSameTargetIsRefusedBeforeCallingProvider() {
        inTxRun(() -> ledger.resolve(started(), new Outcome.Confirmed(APPROVED_AT)));
        inTx(() -> ledger.openRefund(target));

        assertThatThrownBy(() -> inTx(() -> ledger.openRefund(target)))
                .isInstanceOf(ActiveTransactionExistsException.class);
        assertThat(transactions.findTransactionsByTarget(target)).filteredOn(t -> t.type() == TransactionType.REFUND)
                .hasSize(1);
    }

    // ---- 대상당 진행 중 · 성공 CAPTURE 1건 ----

    // 결제창을 두 번 열어 둘 다 인증한 경우. 두 번째를 결제사에 보내면 이중 청구다 — 기록 단계(uq_payment_target)에서는 늦다.
    @Test
    void secondCaptureOfSameTargetCannotStartWhileFirstIsInFlight() {
        PaymentTransaction first = inTx(() -> ledger.openCapture(target, AMOUNT));
        PaymentTransaction second = inTx(() -> ledger.openCapture(target, AMOUNT));
        inTx(() -> ledger.start(first, target, providerPayment, AMOUNT)).orElseThrow();

        assertThatThrownBy(() -> inTx(() -> ledger.start(second, target, PaymentFixtures.newProviderPayment(), AMOUNT)))
                .isInstanceOf(ActiveTransactionExistsException.class);
        assertThat(transactions.findById(second.id()).orElseThrow().status()).isEqualTo(TransactionStatus.PENDING);
    }

    @Test
    void secondCaptureOfSameTargetCannotStartAfterFirstSucceeded() {
        PaymentTransaction second = inTx(() -> ledger.openCapture(target, AMOUNT));
        inTxRun(() -> ledger.resolve(started(), new Outcome.Confirmed(APPROVED_AT)));

        assertThatThrownBy(() -> inTx(() -> ledger.start(second, target, PaymentFixtures.newProviderPayment(), AMOUNT)))
                .isInstanceOf(ActiveTransactionExistsException.class);
    }

    @Test
    void newCaptureCanStartAfterFirstFailed() {
        inTxRun(() -> ledger.resolve(started(), new Outcome.Rejected(REJECT)));
        PaymentTransaction retry = inTx(() -> ledger.openCapture(target, AMOUNT));

        assertThat(inTx(() -> ledger.start(retry, target, PaymentFixtures.newProviderPayment(), AMOUNT))).isPresent();
    }

    @Test
    void captureOfAnotherTargetIsNotStarted() {
        PaymentTransaction pending = inTx(() -> ledger.openCapture(target, AMOUNT));

        assertThatThrownBy(() -> inTx(() -> ledger.start(pending, PaymentFixtures.newDrawEntryTarget(), providerPayment,
                AMOUNT))).isInstanceOf(PaymentTargetMismatchException.class);
        assertThat(transactions.findById(pending.id()).orElseThrow().status()).isEqualTo(TransactionStatus.PENDING);
    }

    @Test
    void transactionsOfTargetAreListedInIdOrder() {
        PaymentTransaction first = inTx(() -> ledger.openCapture(target, AMOUNT));
        PaymentTransaction second = inTx(() -> ledger.openCapture(target, AMOUNT));
        // 앱 시계가 서버마다 달라 created_at 이 거꾸로 찍혀도 순서는 id 다.
        jdbcTemplate.update("UPDATE payment_transactions SET created_at = created_at - INTERVAL 1 HOUR WHERE id = ?",
                second.id());

        assertThat(transactions.findTransactionsByTarget(target)).extracting(PaymentTransaction::id)
                .containsExactly(first.id(), second.id());
        assertThat(transactions.findByProviderOrderId(second.providerOrderId())).map(PaymentTransaction::id)
                .contains(second.id());
    }

    // ---- 만료 ----

    @Test
    void expireClosesUnsentCaptureOnlyAfterItWasOpenedLongEnough() {
        PaymentTransaction pending = inTx(() -> ledger.openCapture(target, AMOUNT));

        assertThat(inTx(() -> ledger.expire(pending, OPENED_FOR))).as("아직 기한 전").isEmpty();
        ageCreatedAt(pending.id(), OPENED_FOR.plusSeconds(1));
        PaymentTransaction expired = inTx(() -> ledger.expire(pending, OPENED_FOR)).orElseThrow();

        assertThat(expired.status()).isEqualTo(TransactionStatus.EXPIRED);
        assertThat(expired.finishedAt()).isNotNull();
        assertThat(expired.attemptCount()).isZero();
        assertThat(expired.providerPaymentKey()).isNull();
        assertThat(inTx(() -> ledger.start(pending, target, providerPayment, AMOUNT)))
                .as("만료된 결제창은 시작되지 않는다").isEmpty();
    }

    // 시작과 만료는 같은 PENDING 조건이다 — 시작이 먼저면 만료는 0행(보낸 적 있는 거래는 결제사가 처리했을 수 있다)
    @Test
    void startedCaptureIsNotExpired() {
        PaymentTransaction pending = inTx(() -> ledger.openCapture(target, AMOUNT));
        ageCreatedAt(pending.id(), OPENED_FOR.plusSeconds(1));
        inTx(() -> ledger.start(pending, target, providerPayment, AMOUNT)).orElseThrow();

        assertThat(inTx(() -> ledger.expire(pending, OPENED_FOR))).isEmpty();
        assertThat(transactions.findById(pending.id()).orElseThrow().status()).isEqualTo(TransactionStatus.PROCESSING);
    }

    // 만료 뒤 같은 대상에 새 결제창을 열고 시작할 수 있다 — EXPIRED 는 대상당 진행 중 거래로 세지 않는다(active_marker)
    @Test
    void expiredCaptureDoesNotBlockTarget() {
        PaymentTransaction pending = inTx(() -> ledger.openCapture(target, AMOUNT));
        ageCreatedAt(pending.id(), OPENED_FOR.plusSeconds(1));
        inTx(() -> ledger.expire(pending, OPENED_FOR)).orElseThrow();

        PaymentTransaction next = inTx(() -> ledger.openCapture(target, AMOUNT));
        assertThat(inTx(() -> ledger.start(next, target, providerPayment, AMOUNT))).isPresent();
    }

    @Test
    void expirableCapturesAreOldUnsentCapturesOnly() {
        PaymentTransaction old = inTx(() -> ledger.openCapture(target, AMOUNT));
        PaymentTransaction fresh = inTx(() -> ledger.openCapture(PaymentFixtures.newOrderTarget(), AMOUNT));
        ageCreatedAt(old.id(), OPENED_FOR.plusSeconds(1));

        assertThat(transactions.findExpirableCaptures(OPENED_FOR, 1000)).extracting(PaymentTransaction::id)
                .contains(old.id()).doesNotContain(fresh.id());
    }

    // ---- 에스컬레이션 · 키 교체 · 복구 후보 ----

    @Test
    void escalatedTransactionKeepsStatusAndLeavesRecoveryCandidates() {
        ClaimedTransaction started = started();
        parkOtherRecoverables();

        inTxRun(() -> ledger.escalate(started, TIMEOUT));
        expireLease(id(started));

        PaymentTransaction escalated = transactions.findById(id(started)).orElseThrow();
        assertThat(escalated.status()).isEqualTo(TransactionStatus.PROCESSING);
        assertThat(escalated.isEscalated()).isTrue();
        assertThat(escalated.lastError()).isEqualTo(TIMEOUT);
        assertThat(inTx(() -> transactions.lockNextRecoverable(TransactionType.CAPTURE))).isEmpty();
    }

    // 다중 방어: 후보 조회가 빼도, 같은 스냅샷으로 claim 해도 에스컬레이션된 거래는 집히지 않는다
    @Test
    void escalatedTransactionIsNotClaimed() {
        ClaimedTransaction started = started();
        inTxRun(() -> ledger.escalate(started, TIMEOUT));
        expireLease(id(started));
        PaymentTransaction seen = transactions.findById(id(started)).orElseThrow();

        assertThat(inTx(() -> ledger.claim(seen))).isEmpty();
    }

    @Test
    void databaseRejectsEscalationOfUnsentTransaction() { // ck_payment_tx_escalated
        PaymentTransaction pending = inTx(() -> ledger.openCapture(target, AMOUNT));

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE payment_transactions SET escalated_at = UTC_TIMESTAMP(6) WHERE id = ?", pending.id()))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_payment_tx_escalated");
    }

    @Test
    void escalateNeedsTheLease() {
        ClaimedTransaction started = started();
        expireLease(id(started));

        assertThatThrownBy(() -> inTxRun(() -> ledger.escalate(started, TIMEOUT)))
                .isInstanceOf(LeaseLostException.class);
        assertThat(transactions.findById(id(started)).orElseThrow().isEscalated()).isFalse();
    }

    // 키 교체는 곧 새 키로 보낸다 — 리스를 새로 잡고(전송이 리스 안에 들게) 마지막 오류를 지운다(멈추면 같은 키로 이어 가게)
    @Test
    void rotatedKeyIsNewWithRenewedLeaseAndNoLastError() {
        ClaimedTransaction started = started();
        inTxRun(() -> ledger.resolve(started, new Outcome.Unknown(TIMEOUT)));
        jdbcTemplate.update("UPDATE payment_transactions SET lease_expires_at = UTC_TIMESTAMP(6) + INTERVAL 5 SECOND "
                + "WHERE id = ?", id(started));

        ClaimedTransaction rotated = inTx(() -> ledger.rotateIdempotencyKey(started));

        assertThat(rotated.transaction().idempotencyKey()).isNotEqualTo(started.transaction().idempotencyKey());
        assertThat(rotated.transaction().leaseToken()).isEqualTo(started.transaction().leaseToken());
        assertThat(rotated.transaction().lastError()).isNull();
        assertThat(leaseSecondsLeft(id(started))).isBetween(PaymentTransaction.LEASE.toSeconds() - 5,
                PaymentTransaction.LEASE.toSeconds());
        expireLease(id(started));
        assertThatThrownBy(() -> inTx(() -> ledger.rotateIdempotencyKey(rotated)))
                .isInstanceOf(LeaseLostException.class);
    }

    @Test
    void recoverableCandidatesFollowClaimReadiness() {
        parkOtherRecoverables();
        ClaimedTransaction inFlight = started();
        assertThat(inTx(() -> transactions.lockNextRecoverable(TransactionType.CAPTURE)))
                .as("리스가 살아 있는 PROCESSING").isEmpty();

        expireLease(id(inFlight));

        assertThat(inTx(() -> transactions.lockNextRecoverable(TransactionType.CAPTURE)))
                .map(PaymentTransaction::id).contains(id(inFlight));
        assertThat(inTx(() -> transactions.lockNextRecoverable(TransactionType.REFUND))).isEmpty();
    }

    private ClaimedTransaction started() {
        PaymentTransaction pending = inTx(() -> ledger.openCapture(target, AMOUNT));
        return inTx(() -> ledger.start(pending, target, providerPayment, AMOUNT)).orElseThrow();
    }

    private static Long id(ClaimedTransaction claimed) {
        return claimed.transaction().id();
    }

    private <T> T inTx(Supplier<T> work) {
        return transactionTemplate.execute(status -> work.get());
    }

    private void inTxRun(Runnable work) {
        transactionTemplate.executeWithoutResult(status -> work.run());
    }

    private long leaseSecondsLeft(Long id) {
        return jdbcTemplate.queryForObject(
                "SELECT TIMESTAMPDIFF(SECOND, UTC_TIMESTAMP(6), lease_expires_at) FROM payment_transactions WHERE id = ?",
                Long.class, id);
    }

    private long secondsUntilRetry(Long id) {
        return jdbcTemplate.queryForObject(
                "SELECT TIMESTAMPDIFF(SECOND, UTC_TIMESTAMP(6), next_retry_at) FROM payment_transactions WHERE id = ?",
                Long.class, id);
    }

    private void expireLease(Long id) {
        jdbcTemplate.update("UPDATE payment_transactions SET lease_expires_at = UTC_TIMESTAMP(6) - INTERVAL 1 SECOND "
                + "WHERE id = ?", id);
    }

    private void makeRetryDue(Long id) {
        jdbcTemplate.update("UPDATE payment_transactions SET next_retry_at = UTC_TIMESTAMP(6) - INTERVAL 1 SECOND "
                + "WHERE id = ?", id);
    }

    private void ageCreatedAt(Long id, Duration age) {
        jdbcTemplate.update("UPDATE payment_transactions SET created_at = UTC_TIMESTAMP(6) - INTERVAL ? SECOND WHERE id = ?",
                age.toSeconds(), id);
    }

    /** 통합 테스트는 커밋된 행을 공유한다. 다른 테스트가 남긴 복구 후보를 에스컬레이션해 이 테스트의 후보만 남긴다. */
    private void parkOtherRecoverables() {
        jdbcTemplate.update("""
                UPDATE payment_transactions SET escalated_at = UTC_TIMESTAMP(6)
                 WHERE escalated_at IS NULL AND status IN ('PROCESSING', 'RETRY_SCHEDULED')
                """);
        jdbcTemplate.update("UPDATE payment_transactions SET status = 'FAILED', finished_at = UTC_TIMESTAMP(6), "
                + "last_error_code = 'TEST_PARKED' WHERE status = 'PENDING' AND transaction_type = 'REFUND'");
    }
}
