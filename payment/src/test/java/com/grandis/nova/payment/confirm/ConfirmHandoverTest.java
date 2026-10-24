package com.grandis.nova.payment.confirm;

import com.grandis.nova.common.UuidBinary;
import com.grandis.nova.common.security.RevocationChecker;
import com.grandis.nova.common.testing.Concurrently;
import com.grandis.nova.payment.ClaimedTransaction;
import com.grandis.nova.payment.PaymentLedger;
import com.grandis.nova.payment.client.toss.TossCommandResult;
import com.grandis.nova.payment.client.toss.TossConfirmRequest;
import com.grandis.nova.payment.client.toss.TossIdempotencyKey;
import com.grandis.nova.payment.client.toss.TossPayment;
import com.grandis.nova.payment.client.toss.TossPaymentClient;
import com.grandis.nova.payment.client.toss.TossPaymentStatus;
import com.grandis.nova.payment.domain.enums.TransactionStatus;
import com.grandis.nova.payment.domain.model.PaymentTransaction;
import com.grandis.nova.payment.domain.repository.PaymentTransactionReader;
import com.grandis.nova.payment.event.PaymentEventDispatcher;
import com.grandis.nova.payment.recovery.RecoveryWorker;
import com.grandis.nova.payment.support.PaymentFixtures;
import com.grandis.nova.payment.support.PaymentIntegrationTest;
import com.grandis.nova.payment.support.RecoveryCandidates;
import com.grandis.nova.payment.vo.Money;
import com.grandis.nova.payment.vo.PaymentTarget;
import com.grandis.nova.payment.vo.ProviderPaymentKey;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 승인 요청이 닿지 않은 결제창의 넘김. 결제창 확인(확보)이 결제 키를 적고, 승인 중 알림(ORDER_PAYMENT_AUTHORIZING)이 확보 뒤 90초가 지나도
 * 시작 전인 결제창을 "리스가 이미 끝난 PROCESSING" 으로 넘겨 복구가 첫 승인을 보낸다. 확보 시각은 행의 reserved_at 을 당겨 만든다.
 * MySQL 위에서 돌고 토스 클라이언트만 대역이다.
 */
@PaymentIntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class ConfirmHandoverTest {

    static final Money AMOUNT = Money.won(15000);
    static final Instant APPROVED_AT = Instant.parse("2026-10-23T03:04:05Z");
    static final Duration PAST_DIRECT_FIRST = ConfirmHandover.DIRECT_FIRST.plusSeconds(30);

    @Autowired
    ConfirmHandover handover;

    @Autowired
    ConfirmPaymentService confirmService;

    @Autowired
    PaymentEventDispatcher dispatcher;

    @Autowired
    RecoveryWorker worker;

    @Autowired
    PaymentLedger ledger;

    @MockitoSpyBean
    PaymentTransactionReader transactions;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    JsonMapper jsonMapper;

    @Autowired
    MeterRegistry registry;

    @MockitoBean
    RevocationChecker revocationChecker;

    @MockitoBean
    TossPaymentClient toss;

    PaymentTarget target;
    ProviderPaymentKey paymentKey;
    PaymentTransaction opened;

    @BeforeEach
    void setUp() {
        target = PaymentFixtures.newOrderTarget();
        paymentKey = PaymentFixtures.newProviderPayment();
        opened = transactionTemplate.execute(s -> ledger.openCapture(target, AMOUNT));
    }

    // ---- 확인 단계의 키 저장 ----

    @Test
    void reservingCheckStoresPaymentKeyOnPendingCapture() {
        reserveCheck(paymentKey, AMOUNT);

        PaymentTransaction after = reload();
        assertThat(after.status()).isEqualTo(TransactionStatus.PENDING);
        assertThat(after.providerPaymentKey()).isEqualTo(paymentKey);
        assertThat(after.attemptCount()).isZero();
        verify(toss, never()).confirm(any(), any());
    }

    // 응모비 결제도 같은 확인을 쓴다 — 키를 적어도 시작하지 않으므로 동작은 같다
    @Test
    void reservingCheckOfDrawEntryAlsoStoresKeyWithoutStarting() {
        PaymentTarget entry = PaymentFixtures.newDrawEntryTarget();
        PaymentTransaction entryAttempt = transactionTemplate.execute(s -> ledger.openCapture(entry, AMOUNT));

        ConfirmResult result = confirmService.confirm(entryAttempt.providerOrderId(), entry, paymentKey, AMOUNT, false, true);

        assertThat(result.result()).isEqualTo(ConfirmResult.Result.PENDING);
        PaymentTransaction after = transactions.findById(entryAttempt.id()).orElseThrow();
        assertThat(after.status()).isEqualTo(TransactionStatus.PENDING);
        assertThat(after.providerPaymentKey()).isEqualTo(paymentKey);
    }

    // 확인은 금액을 보지 않고 키를 적는다 — 저장 금액이 결제창 금액과 다르면 알림의 금액으로 넘김 UPDATE 가 막는다
    @Test
    void reservingCheckWithOtherAmountStoresKeyButHandOverRefuses() {
        reserveCheck(paymentKey, Money.won(99999));
        assertThat(reload().providerPaymentKey()).isEqualTo(paymentKey);
        ageReservedAt(PAST_DIRECT_FIRST);

        assertThat(handover.handle(target, opened.providerOrderId(), Money.won(99999))).isEmpty();

        assertThat(reload().status()).isEqualTo(TransactionStatus.PENDING);
    }

    @Test
    void reservingAgainWithSameKeyKeepsIt() {
        reserveCheck(paymentKey, AMOUNT);
        reserveCheck(paymentKey, AMOUNT);

        assertThat(reload().providerPaymentKey()).isEqualTo(paymentKey);
    }

    // 다른 키가 오면 어느 쪽이 승인 중 전환과 짝인지 모른다 — 지우고 넘기지 않는다. 지운 뒤 다시 와도 적지 않는다
    @Test
    void reservingAgainWithOtherKeyClearsItForGood() {
        reserveCheck(paymentKey, AMOUNT);
        reserveCheck(PaymentFixtures.newProviderPayment(), AMOUNT);
        assertThat(reload().providerPaymentKey()).isNull();

        reserveCheck(paymentKey, AMOUNT);
        assertThat(reload().providerPaymentKey()).isNull();
    }

    // 시작은 그대로 받은 키로 한다 — 확보 때 적은 키가 시작을 막거나 바꾸지 않는다
    @Test
    void startUsesTheKeyOfTheStartingRequest() {
        reserveCheck(paymentKey, AMOUNT);
        ProviderPaymentKey later = PaymentFixtures.newProviderPayment();

        transactionTemplate.execute(s -> ledger.start(reload(), target, later, AMOUNT)).orElseThrow();

        assertThat(reload().providerPaymentKey()).isEqualTo(later);
    }

    // 넘김은 시작과 같이 금액을 대조한다 — 알림의 주문 총액이 결제창 금액과 다르면 넘기지 않는다(처리기 · UPDATE 둘 다)
    @Test
    void noticeWithOtherAmountIsNotHandedOver() {
        reserveCheck(paymentKey, AMOUNT);
        ageReservedAt(PAST_DIRECT_FIRST);
        double handed = count(UnreachedConfirmMetrics.Result.HANDED_OVER);

        assertThat(handover.handle(target, opened.providerOrderId(), Money.won(99999))).isEmpty();
        PaymentTransaction seen = reload();
        Boolean byUpdate = transactionTemplate.execute(s -> ledger.handOver(seen, Money.won(99999),
                ConfirmHandover.DIRECT_FIRST, CaptureRecovery.APPROVAL_WINDOW));
        assertThat(byUpdate).as("원장은 거르지 않고 UPDATE 가 행의 금액으로 막는다").isFalse();

        assertThat(reload().status()).isEqualTo(TransactionStatus.PENDING);
        assertThat(count(UnreachedConfirmMetrics.Result.HANDED_OVER)).isEqualTo(handed);
    }

    // 승인 중 재요청이 시작 금지(확보 없음)로 물으면 넘김을 끈다 — 결제할 수 없게 된 주문을 이미 나간 알림이 청구하지 않게
    @Test
    void recoverOnlyRequestDisablesHandover() {
        reserveCheck(paymentKey, AMOUNT);

        ConfirmResult result = confirmService.confirm(opened.providerOrderId(), target, paymentKey, AMOUNT, false, false);

        assertThat(result.result()).isEqualTo(ConfirmResult.Result.PENDING);
        assertThat(reload().providerPaymentKey()).isNull();
        ageReservedAt(PAST_DIRECT_FIRST);
        assertThat(handover.handle(target, opened.providerOrderId(), AMOUNT)).isEmpty();
        assertThat(reload().status()).isEqualTo(TransactionStatus.PENDING);
        reserveCheck(paymentKey, AMOUNT);
        assertThat(reload().providerPaymentKey()).as("지운 키는 다시 적지 않는다").isNull();
        verify(toss, never()).confirm(any(), any());
    }

    // 이미 넘겨졌으면 시작 금지 재요청은 아무것도 바꾸지 않고 지금 결과를 돌려준다(승인되면 order 의 기존 흐름이 환불한다)
    @Test
    void recoverOnlyRequestAfterHandoverLeavesItAlone() {
        reserveCheck(paymentKey, AMOUNT);
        ageReservedAt(PAST_DIRECT_FIRST);
        handover.handle(target, opened.providerOrderId(), AMOUNT);

        ConfirmResult result = confirmService.confirm(opened.providerOrderId(), target, paymentKey, AMOUNT, false, false);

        assertThat(result.result()).isEqualTo(ConfirmResult.Result.PENDING);
        PaymentTransaction after = reload();
        assertThat(after.status()).isEqualTo(TransactionStatus.PROCESSING);
        assertThat(after.providerPaymentKey()).isEqualTo(paymentKey);
    }

    // 넘김과 넘김 끄기는 같은 PENDING 조건이라 한쪽만 된다
    @RepeatedTest(5)
    void handOverAndDisableRacingLeaveOne() throws Exception {
        reserveCheck(paymentKey, AMOUNT);
        ageReservedAt(PAST_DIRECT_FIRST);
        PaymentTransaction seen = reload();

        List<Concurrently.Outcome<Boolean>> outcomes = Concurrently.run(2, i -> () -> i == 0
                ? transactionTemplate.execute(s -> ledger.handOver(seen, AMOUNT, ConfirmHandover.DIRECT_FIRST,
                        CaptureRecovery.APPROVAL_WINDOW))
                : transactionTemplate.execute(s -> ledger.disableHandover(seen)));

        assertThat(outcomes).allSatisfy(o -> assertThat(o.error()).isNull());
        boolean handed = outcomes.get(0).value();
        assertThat(handed).isNotEqualTo(outcomes.get(1).value());
        PaymentTransaction after = reload();
        assertThat(after.status()).isEqualTo(handed ? TransactionStatus.PROCESSING : TransactionStatus.PENDING);
        assertThat(after.providerPaymentKey()).isEqualTo(handed ? paymentKey : null);
    }

    // 다른 키의 확인 둘이 겹쳐도 키가 남지 않는다 — 확보는 행 잠금 뒤 조건을 다시 보므로 늦은 쪽이 다른 키를 보고 지운다
    @RepeatedTest(5)
    void concurrentReservesWithDifferentKeysLeaveNoKey() throws Exception {
        ProviderPaymentKey other = PaymentFixtures.newProviderPayment();

        List<Concurrently.Outcome<ConfirmResult>> outcomes = Concurrently.run(2, i -> () -> confirmService.confirm(
                opened.providerOrderId(), target, i == 0 ? paymentKey : other, AMOUNT, false, true));

        assertThat(outcomes).allSatisfy(o -> assertThat(o.error()).isNull());
        assertThat(reload().providerPaymentKey()).isNull();
        assertThat(reservedAt()).isNotNull();
    }

    // ---- 넘김 · 만료의 시각 경계(UPDATE 조건) ----

    // 읽은 뒤 다시 확보됐으면(90초 전) UPDATE 가 넘기지 않는다 — 처리기의 판정을 건너뛰고 원장을 직접 부른다
    @Test
    void handOverSqlRefusesCaptureReservedWithinDirectFirst() {
        reserveCheck(paymentKey, AMOUNT);
        ageReservedAt(Duration.ofSeconds(30));

        assertThat(handOverDirectly()).isFalse();
        assertThat(reload().status()).isEqualTo(TransactionStatus.PENDING);
    }

    // 확보 뒤 10분 직전은 넘김만, 직후는 둘 다 아님, 35분 뒤는 만료만 — 두 조건이 같은 시각에 겹치지 않는다
    @Test
    void handOverAndExpiryWindowsDoNotOverlapAroundTenMinutes() {
        reserveCheck(paymentKey, AMOUNT);
        ageReservedAt(CaptureRecovery.APPROVAL_WINDOW.minusSeconds(10));
        assertThat(expireDirectly()).isFalse();
        assertThat(handOverDirectly()).isTrue();

        opened = transactionTemplate.execute(s -> ledger.openCapture(target, AMOUNT));
        reserveCheck(paymentKey, AMOUNT);
        ageReservedAt(CaptureRecovery.APPROVAL_WINDOW.plusSeconds(10));
        assertThat(handOverDirectly()).isFalse();
        assertThat(expireDirectly()).isFalse();
        assertThat(reload().status()).isEqualTo(TransactionStatus.PENDING);

        ageReservedAt(PendingCaptureExpiry.OPENED_FOR.plusMinutes(1));
        assertThat(handOverDirectly()).isFalse();
        assertThat(expireDirectly()).isTrue();
        assertThat(reload().status()).isEqualTo(TransactionStatus.EXPIRED);
    }

    // ---- 처리기의 갈래 ----

    // 이르면 남은 시간만큼 미룬다 — 그동안 직접 승인 요청이 먼저 시작한다
    @Test
    void earlyNoticeIsPostponedByTheRemainingTime() {
        reserveCheck(paymentKey, AMOUNT);
        ageReservedAt(Duration.ofSeconds(30));
        double handed = count(UnreachedConfirmMetrics.Result.HANDED_OVER);

        Optional<Duration> later = handover.handle(target, opened.providerOrderId(), AMOUNT);

        assertThat(later).hasValueSatisfying(delay -> assertThat(delay)
                .isGreaterThan(Duration.ofSeconds(55)).isLessThanOrEqualTo(Duration.ofSeconds(60)));
        assertThat(reload().status()).isEqualTo(TransactionStatus.PENDING);
        assertThat(count(UnreachedConfirmMetrics.Result.HANDED_OVER)).isEqualTo(handed);
    }

    // 확보 시각을 읽은 뒤 넘기기 전에 같은 키로 다시 확보되면, 넘김 UPDATE 는 0행이지만 알림을 끝내지 않고 남은 시간만큼 미룬다
    @Test
    void reservedAgainBeforeHandOverIsPostponedNotDropped() {
        reserveCheck(paymentKey, AMOUNT);
        doReturn(Optional.of(PAST_DIRECT_FIRST)).doCallRealMethod().when(transactions).sinceReserved(opened.id());

        Optional<Duration> later = handover.handle(target, opened.providerOrderId(), AMOUNT);

        assertThat(later).hasValueSatisfying(delay -> assertThat(delay)
                .isPositive().isLessThanOrEqualTo(ConfirmHandover.DIRECT_FIRST));
        assertThat(reload().status()).isEqualTo(TransactionStatus.PENDING);
    }

    @Test
    void startedCaptureIsLeftAlone() {
        reserveCheck(paymentKey, AMOUNT);
        ClaimedTransaction started = transactionTemplate.execute(s -> ledger.start(reload(), target, paymentKey, AMOUNT))
                .orElseThrow();
        ageReservedAt(PAST_DIRECT_FIRST);

        assertThat(handover.handle(target, opened.providerOrderId(), AMOUNT)).isEmpty();

        PaymentTransaction after = reload();
        assertThat(after.status()).isEqualTo(TransactionStatus.PROCESSING);
        assertThat(after.leaseToken()).isEqualTo(started.transaction().leaseToken());
        assertThat(after.attemptCount()).isEqualTo(1);
    }

    @Test
    void unreachedCaptureIsHandedOverAsLeaseExpiredProcessing(CapturedOutput output) {
        reserveCheck(paymentKey, AMOUNT);
        ageReservedAt(PAST_DIRECT_FIRST);
        double handed = count(UnreachedConfirmMetrics.Result.HANDED_OVER);

        assertThat(handover.handle(target, opened.providerOrderId(), AMOUNT)).isEmpty();

        PaymentTransaction after = reload();
        assertThat(after.status()).isEqualTo(TransactionStatus.PROCESSING);
        assertThat(after.attemptCount()).isEqualTo(1);
        assertThat(after.requestedAt()).isNotNull();
        assertThat(after.lastError()).isNull();
        assertThat(after.leaseToken()).isNotNull();
        assertThat(leaseExpired()).as("리스가 이미 끝나 복구가 바로 집는다").isTrue();
        assertThat(after.idempotencyKey()).isEqualTo(opened.idempotencyKey());
        assertThat(count(UnreachedConfirmMetrics.Result.HANDED_OVER)).isEqualTo(handed + 1);
        assertThat(output.getAll()).doesNotContain(paymentKey.value());
    }

    // 넘긴 거래는 복구가 확보 때의 키 · 거래의 멱등 키로 첫 승인을 보내고, 결과를 기존 길로 대상에 알린다
    @Test
    void handedOverCaptureIsApprovedByRecoveryWithStoredKey() {
        reserveCheck(paymentKey, AMOUNT);
        ageReservedAt(PAST_DIRECT_FIRST);
        RecoveryCandidates.parkOthers(jdbcTemplate);
        handover.handle(target, opened.providerOrderId(), AMOUNT);
        given(toss.confirm(any(), any())).willReturn(done());

        worker.recoverDue();

        verify(toss).confirm(new TossConfirmRequest(paymentKey.value(), opened.providerOrderId().value(), 15000),
                new TossIdempotencyKey(opened.idempotencyKey().value()));
        assertThat(reload().status()).isEqualTo(TransactionStatus.SUCCEEDED);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payments WHERE target_type = 'ORDER' AND target_id = ?", Integer.class,
                UuidBinary.toBytes(target.id()))).isEqualTo(1);
        JsonNode settled = settledPayload();
        assertThat(settled.get("result").asString()).isEqualTo("APPROVED");
        assertThat(settled.get("providerOrderId").asString()).isEqualTo(opened.providerOrderId().value());
    }

    // 토스 승인 창(확보 뒤 10분)이 지났으면 넘기지 않는다 — 만료가 닫는다
    @Test
    void captureBeyondApprovalWindowIsNotHandedOver() {
        reserveCheck(paymentKey, AMOUNT);
        ageReservedAt(CaptureRecovery.APPROVAL_WINDOW.plusSeconds(30));
        double tooLate = count(UnreachedConfirmMetrics.Result.TOO_LATE);
        double handed = count(UnreachedConfirmMetrics.Result.HANDED_OVER);

        assertThat(handover.handle(target, opened.providerOrderId(), AMOUNT)).isEmpty();

        assertThat(reload().status()).isEqualTo(TransactionStatus.PENDING);
        assertThat(count(UnreachedConfirmMetrics.Result.TOO_LATE)).isEqualTo(tooLate + 1);
        assertThat(count(UnreachedConfirmMetrics.Result.HANDED_OVER)).isEqualTo(handed);
    }

    // 확보 때의 키가 지워졌으면(다른 키가 왔다) 넘기지 않는다 — 처리기 · UPDATE 둘 다
    @Test
    void captureWithoutStoredKeyIsNotHandedOver() {
        reserveCheck(paymentKey, AMOUNT);
        reserveCheck(PaymentFixtures.newProviderPayment(), AMOUNT);
        ageReservedAt(PAST_DIRECT_FIRST);
        PaymentTransaction keyless = reload();
        Boolean byUpdate = transactionTemplate.execute(s -> ledger.handOver(keyless, AMOUNT, ConfirmHandover.DIRECT_FIRST,
                CaptureRecovery.APPROVAL_WINDOW));
        assertThat(byUpdate).isFalse();
        double handed = count(UnreachedConfirmMetrics.Result.HANDED_OVER);

        assertThat(handover.handle(target, opened.providerOrderId(), AMOUNT)).isEmpty();

        assertThat(reload().status()).isEqualTo(TransactionStatus.PENDING);
        assertThat(count(UnreachedConfirmMetrics.Result.HANDED_OVER)).isEqualTo(handed);
    }

    // 넘김과 직접 승인 요청의 시작은 같은 PENDING 조건이라 한쪽만 된다
    @RepeatedTest(5)
    void handOverAndDirectStartRacingLeaveOne() throws Exception {
        reserveCheck(paymentKey, AMOUNT);
        ageReservedAt(PAST_DIRECT_FIRST);
        PaymentTransaction seen = reload();

        List<Concurrently.Outcome<Boolean>> outcomes = Concurrently.run(2, i -> () -> i == 0
                ? transactionTemplate.execute(s -> ledger.handOver(seen, AMOUNT, ConfirmHandover.DIRECT_FIRST, CaptureRecovery.APPROVAL_WINDOW))
                : transactionTemplate.execute(s -> ledger.start(seen, target, paymentKey, AMOUNT)).isPresent());

        assertThat(outcomes).allSatisfy(o -> assertThat(o.error()).isNull());
        assertThat(outcomes.get(0).value()).isNotEqualTo(outcomes.get(1).value());
        PaymentTransaction after = reload();
        assertThat(after.status()).isEqualTo(TransactionStatus.PROCESSING);
        assertThat(after.attemptCount()).isEqualTo(1);
    }

    // ---- 받기(분배기) ----

    // 같은 알림을 두 번 받아도 한 번 넘긴 것과 같다
    @Test
    void repeatedNoticeHandsOverOnce() {
        reserveCheck(paymentKey, AMOUNT);
        ageReservedAt(PAST_DIRECT_FIRST);
        double handed = count(UnreachedConfirmMetrics.Result.HANDED_OVER);
        String body = authorizing(target.id(), opened.providerOrderId().value());

        assertThat(dispatcher.dispatch(body)).isEmpty();
        PaymentTransaction first = reload();
        assertThat(dispatcher.dispatch(body)).isEmpty();

        PaymentTransaction after = reload();
        assertThat(after.status()).isEqualTo(TransactionStatus.PROCESSING);
        assertThat(after.attemptCount()).isEqualTo(1);
        assertThat(after.leaseToken()).isEqualTo(first.leaseToken());
        assertThat(count(UnreachedConfirmMetrics.Result.HANDED_OVER)).isEqualTo(handed + 1);
    }

    @Test
    void earlyNoticeIsReturnedAsDelayByDispatcher() {
        reserveCheck(paymentKey, AMOUNT);

        assertThat(dispatcher.dispatch(authorizing(target.id(), opened.providerOrderId().value()))).isPresent();
        assertThat(reload().status()).isEqualTo(TransactionStatus.PENDING);
    }

    // 다른 주문의 결제창을 넘기지 않는다
    @Test
    void noticeForAnotherOrderIsRefused() {
        reserveCheck(paymentKey, AMOUNT);
        ageReservedAt(PAST_DIRECT_FIRST);

        assertThatThrownBy(() -> dispatcher.dispatch(authorizing(UUID.randomUUID(), opened.providerOrderId().value())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(reload().status()).isEqualTo(TransactionStatus.PENDING);
    }

    // order 는 확인한 결제창으로만 승인 중이 된다 — 모르는 번호는 계약이 어긋난 것이라 실패로 올린다(DLQ)
    @Test
    void noticeForUnknownCaptureFailsLoudly() {
        assertThatThrownBy(() -> dispatcher.dispatch(authorizing(target.id(), UUID.randomUUID().toString())))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void outcomesArePreregisteredAtZero() {
        for (UnreachedConfirmMetrics.Result result : UnreachedConfirmMetrics.Result.values()) {
            assertThat(registry.find(UnreachedConfirmMetrics.METER).tag("result", result.name()).counter()).isNotNull();
        }
    }

    // ---- 도우미 ----

    private void reserveCheck(ProviderPaymentKey key, Money amount) {
        ConfirmResult result = confirmService.confirm(opened.providerOrderId(), target, key, amount, false, true);
        assertThat(result.result()).isEqualTo(ConfirmResult.Result.PENDING);
    }

    private boolean handOverDirectly() {
        PaymentTransaction seen = reload();
        return Boolean.TRUE.equals(transactionTemplate.execute(s ->
                ledger.handOver(seen, AMOUNT, ConfirmHandover.DIRECT_FIRST, CaptureRecovery.APPROVAL_WINDOW)));
    }

    private boolean expireDirectly() {
        PaymentTransaction seen = reload();
        return transactionTemplate.execute(s -> ledger.expire(seen, PendingCaptureExpiry.OPENED_FOR)).isPresent();
    }

    private PaymentTransaction reload() {
        return transactions.findById(opened.id()).orElseThrow();
    }

    private Object reservedAt() {
        return jdbcTemplate.queryForObject("SELECT reserved_at FROM payment_transactions WHERE id = ?", Object.class,
                UuidBinary.toBytes(opened.id()));
    }

    private boolean leaseExpired() {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT lease_expires_at <= UTC_TIMESTAMP(6) FROM payment_transactions WHERE id = ?", Boolean.class,
                UuidBinary.toBytes(opened.id())));
    }

    private void ageReservedAt(Duration age) {
        jdbcTemplate.update("UPDATE payment_transactions SET reserved_at = UTC_TIMESTAMP(6) - INTERVAL ? SECOND WHERE id = ?",
                age.toSeconds(), UuidBinary.toBytes(opened.id()));
    }

    private double count(UnreachedConfirmMetrics.Result result) {
        return registry.get(UnreachedConfirmMetrics.METER).tag("result", result.name()).counter().count();
    }

    private TossCommandResult done() {
        return new TossCommandResult.Succeeded(new TossPayment(paymentKey.value(), opened.providerOrderId().value(),
                TossPaymentStatus.DONE, 15000, 15000, "카드", APPROVED_AT, "tx", null));
    }

    private JsonNode settledPayload() {
        List<String> payloads = jdbcTemplate.queryForList("""
                SELECT payload FROM payment_outbox_events
                 WHERE aggregate_type = 'ORDER' AND aggregate_id = ? AND event_type = 'ORDER_PAYMENT_SETTLED'
                """, String.class, UuidBinary.toBytes(target.id()));
        assertThat(payloads).hasSize(1);
        assertThat(payloads.getFirst()).doesNotContain(paymentKey.value());
        return jsonMapper.readTree(payloads.getFirst());
    }

    static String authorizing(UUID orderId, String providerOrderId) {
        return """
                {"eventId":"%s","eventType":"ORDER_PAYMENT_AUTHORIZING","aggregateType":"ORDER","aggregateId":"%s",
                 "occurredAt":"2026-10-23T01:00:00Z","payload":{"providerOrderId":"%s","amount":15000}}
                """.formatted(UUID.randomUUID(), orderId, providerOrderId);
    }
}
