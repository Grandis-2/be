package com.grandis.nova.payment.refund;

import com.grandis.nova.common.security.RevocationChecker;
import com.grandis.nova.common.testing.Concurrently;
import com.grandis.nova.payment.ClaimedTransaction;
import com.grandis.nova.payment.PaymentLedger;
import com.grandis.nova.payment.client.toss.TossCancelRequest;
import com.grandis.nova.payment.client.toss.TossCommandResult;
import com.grandis.nova.payment.client.toss.TossIdempotencyKey;
import com.grandis.nova.payment.client.toss.TossLookupResult;
import com.grandis.nova.payment.client.toss.TossPayment;
import com.grandis.nova.payment.client.toss.TossPaymentClient;
import com.grandis.nova.payment.client.toss.TossPaymentStatus;
import com.grandis.nova.payment.client.toss.UnknownReason;
import com.grandis.nova.payment.domain.enums.PaymentStatus;
import com.grandis.nova.payment.domain.enums.TransactionStatus;
import com.grandis.nova.payment.domain.exception.LeaseLostException;
import com.grandis.nova.payment.domain.model.Outcome;
import com.grandis.nova.payment.domain.model.PaymentTransaction;
import com.grandis.nova.payment.domain.repository.PaymentReader;
import com.grandis.nova.payment.domain.repository.PaymentTransactionReader;
import com.grandis.nova.payment.recovery.RecoveryWorker;
import com.grandis.nova.payment.support.PaymentFixtures;
import com.grandis.nova.payment.support.PaymentIntegrationTest;
import com.grandis.nova.payment.support.RecoveryCandidates;
import com.grandis.nova.payment.vo.Money;
import com.grandis.nova.payment.vo.PaymentTarget;
import com.grandis.nova.payment.vo.ProviderError;
import com.grandis.nova.payment.vo.ProviderPaymentKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 환불 실행 · 결과 불명 복구 — 에픽 계획서 "결과 불명 해소 2단계 계약"의 취소 행을 여기 고정한다. MySQL 위에서 돌고 토스 클라이언트만
 * 대역이다(HTTP → 분류는 TossPaymentClientTest). 승인된 결제에 환불을 열어 두고 워커를 돌린다. 리스 만료 · 재시도 시각 도래 · 첫 전송 뒤
 * 경과는 행의 시각을 당겨 만든다. 통합 테스트는 커밋된 행을 공유하므로, 워커를 돌리기 전에 다른 테스트가 남긴 후보를 치운다.
 */
@PaymentIntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class RefundRecoveryTest {

    static final Money AMOUNT = Money.won(15000);
    static final Instant APPROVED_AT = Instant.parse("2026-10-04T01:00:00Z");
    static final Instant CANCELED_AT = Instant.parse("2026-10-04T01:20:30Z");
    static final ProviderError TIMEOUT = new ProviderError(UnknownReason.TIMEOUT.name(), UnknownReason.TIMEOUT.name());
    static final ProviderError PROVIDER_ERROR = new ProviderError("PROVIDER_ERROR", "일시적인 오류");
    static final ProviderError BUSY = new ProviderError("IDEMPOTENT_REQUEST_PROCESSING", null);
    static final ProviderError ALREADY_CANCELED =
            new ProviderError("ALREADY_CANCELED_PAYMENT", UnknownReason.LISTED_CODE.name());
    static final String NOT_CANCELABLE = "NOT_CANCELABLE_PAYMENT";

    @Autowired
    RecoveryWorker worker;

    @Autowired
    PaymentLedger ledger;

    @Autowired
    PaymentTransactionReader transactions;

    @Autowired
    PaymentReader payments;

    @Autowired
    RefundSettlement settlement;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    JsonMapper jsonMapper;

    @MockitoBean
    RevocationChecker revocationChecker;

    @MockitoBean
    TossPaymentClient toss;

    PaymentTarget target;
    ProviderPaymentKey paymentKey;

    @BeforeEach
    void setUp() {
        target = PaymentFixtures.newOrderTarget();
        paymentKey = PaymentFixtures.newProviderPayment();
        RecoveryCandidates.parkOthers(jdbcTemplate);
    }

    // ---- 첫 전송 ----

    @Test
    void openedRefundIsSentAndCompletes(CapturedOutput output) {
        PaymentTransaction refund = refundOpened();
        given(toss.cancel(any(), any())).willReturn(canceled(AMOUNT));

        worker.recoverDue();

        verify(toss).cancel(new TossCancelRequest(paymentKey.value(), RefundRecovery.CANCEL_REASON),
                new TossIdempotencyKey(refund.idempotencyKey().value()));
        verify(toss, never()).findByPaymentKey(anyString());
        assertThat(statusOf(refund)).isEqualTo(TransactionStatus.SUCCEEDED);
        assertThat(payments.findPaymentByTarget(target).orElseThrow().status()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(payments.findPaymentByTarget(target).orElseThrow().refundedAt()).isEqualTo(CANCELED_AT);
        JsonNode settled = settledPayload();
        assertThat(settled.get("result").asString()).isEqualTo("REFUNDED");
        assertThat(settled.get("amount").decimalValue()).isEqualByComparingTo("15000");
        assertThat(Instant.parse(settled.get("refundedAt").asString())).isEqualTo(CANCELED_AT);
        assertThat(output.getAll()).doesNotContain(paymentKey.value());
    }

    // ---- 1단계: 같은 키 재전송 ----

    @Test
    void timeoutIsResentWithSameKey() {
        PaymentTransaction refund = sentThenLeaseExpired(TIMEOUT);
        given(toss.cancel(any(), any())).willReturn(canceled(AMOUNT));

        worker.recoverDue();

        verify(toss).cancel(any(), any(TossIdempotencyKey.class));
        verify(toss, never()).findByPaymentKey(anyString());
        assertThat(keyOf(refund)).isEqualTo(refund.idempotencyKey().value());
        assertThat(statusOf(refund)).isEqualTo(TransactionStatus.SUCCEEDED);
    }

    // 결과를 남기기 전에 멈췄다(마지막 오류 없음) — 응답을 받지 못했을 수 있다
    @Test
    void crashBeforeResultIsResentWithSameKey() {
        PaymentTransaction refund = sentThenLeaseExpired(null);
        given(toss.cancel(any(), any())).willReturn(canceled(AMOUNT));

        worker.recoverDue();

        verify(toss).cancel(any(), any());
        verify(toss, never()).findByPaymentKey(anyString());
        assertThat(statusOf(refund)).isEqualTo(TransactionStatus.SUCCEEDED);
    }

    @Test
    void sameKeyStillProcessingIsRescheduledAndResentWithSameKey() {
        PaymentTransaction refund = refundOpened();
        given(toss.cancel(any(), any())).willReturn(new TossCommandResult.Processing(BUSY.code()));

        worker.recoverDue();

        assertThat(statusOf(refund)).isEqualTo(TransactionStatus.RETRY_SCHEDULED);
        makeRetryDue(refund.id());
        given(toss.cancel(any(), any())).willReturn(canceled(AMOUNT));

        worker.recoverDue();

        verify(toss, times(2)).cancel(any(), any());
        verify(toss, never()).findByPaymentKey(anyString());
        assertThat(keyOf(refund)).isEqualTo(refund.idempotencyKey().value());
        assertThat(statusOf(refund)).isEqualTo(TransactionStatus.SUCCEEDED);
    }

    // 응답을 못 받았다 — 리스(70초) 주기가 아니라 늘어나는 간격으로 같은 키를 다시 보낸다(리뷰 M1)
    @Test
    void unansweredSendIsResentLaterWithSameKey() {
        PaymentTransaction refund = refundOpened();
        given(toss.cancel(any(), any())).willReturn(new TossCommandResult.Unknown(UnknownReason.TIMEOUT, null));

        worker.recoverDue();

        assertThat(statusOf(refund)).isEqualTo(TransactionStatus.RETRY_SCHEDULED);
        assertThat(secondsUntilRetry(refund.id())).isBetween(RefundRecovery.FIRST_RETRY_AFTER.toSeconds() - 5,
                RefundRecovery.FIRST_RETRY_AFTER.toSeconds());
        assertThat(settledEvents()).isZero();
        makeRetryDue(refund.id());
        given(toss.cancel(any(), any())).willReturn(canceled(AMOUNT));

        worker.recoverDue();

        verify(toss, times(2)).cancel(any(), eq(new TossIdempotencyKey(refund.idempotencyKey().value())));
        verify(toss, never()).findByPaymentKey(anyString());
        assertThat(statusOf(refund)).isEqualTo(TransactionStatus.SUCCEEDED);
    }

    // 해석할 수 없는 응답(게이트웨이 오류 등) — 그 요청이 토스 안에서 아직 처리 중일 수 있다. 끝날 여유를 둔 뒤에야 조회 · 교체한다(리뷰 H1)
    @Test
    void unreadableSendIsLookedUpOnlyAfterSettleGrace() {
        PaymentTransaction refund = refundOpened();
        given(toss.cancel(any(), any()))
                .willReturn(new TossCommandResult.Unknown(UnknownReason.UNREADABLE_RESPONSE, null));

        worker.recoverDue();

        assertThat(statusOf(refund)).isEqualTo(TransactionStatus.RETRY_SCHEDULED);
        assertThat(secondsUntilRetry(refund.id())).isBetween(RefundRecovery.SETTLE_GRACE.toSeconds() - 5,
                RefundRecovery.SETTLE_GRACE.toSeconds());
        verify(toss, never()).findByPaymentKey(anyString());
        assertThat(keyOf(refund)).isEqualTo(refund.idempotencyKey().value());
    }

    // ---- 1단계: 조회 ----

    // 토스는 오류 응답도 키에 캐시한다 — 일시 오류 뒤에는 조회로 미처리를 보고 같은 행에서 키를 바꿔 보낸다
    @Test
    void transientErrorIsLookedUpThenResentWithNewKey() {
        PaymentTransaction refund = sentThenRetryDue(PROVIDER_ERROR);
        given(toss.findByPaymentKey(paymentKey.value())).willReturn(found(TossPaymentStatus.DONE));
        given(toss.cancel(any(), any())).willReturn(canceled(AMOUNT));

        worker.recoverDue();

        String rotated = keyOf(refund);
        assertThat(rotated).isNotEqualTo(refund.idempotencyKey().value());
        verify(toss).cancel(any(), eq(new TossIdempotencyKey(rotated)));
        assertThat(statusOf(refund)).isEqualTo(TransactionStatus.SUCCEEDED);
    }

    // 키를 바꿔 보낸 회차에 또 일시 오류면 그 회차는 끝낸다 — 다음 회차가 조회부터 한다(한 회차에 교체 한 번).
    // 일시 오류도 "토스가 처리하지 않았다"의 증명은 아니다(앞 계층의 연속 요청 제한 · 캐시되지 않은 5xx) — 여유를 둔 뒤에야 조회한다
    @Test
    void transientAfterRotationEndsTheRoundAndWaitsSettleGrace() {
        PaymentTransaction refund = sentThenRetryDue(PROVIDER_ERROR);
        given(toss.findByPaymentKey(paymentKey.value())).willReturn(found(TossPaymentStatus.DONE));
        given(toss.cancel(any(), any())).willReturn(new TossCommandResult.Transient("PROVIDER_ERROR", "일시적인 오류"));

        worker.recoverDue();

        verify(toss).cancel(any(), any());
        assertThat(statusOf(refund)).isEqualTo(TransactionStatus.RETRY_SCHEDULED);
        assertThat(secondsUntilRetry(refund.id())).isBetween(RefundRecovery.SETTLE_GRACE.toSeconds() - 5,
                RefundRecovery.SETTLE_GRACE.toSeconds());
    }

    @Test
    void alreadyCanceledIsCompletedByLookup() {
        PaymentTransaction refund = sentThenLeaseExpired(ALREADY_CANCELED);
        given(toss.findByPaymentKey(paymentKey.value())).willReturn(found(TossPaymentStatus.CANCELED));

        worker.recoverDue();

        verify(toss, never()).cancel(any(), any());
        assertThat(statusOf(refund)).isEqualTo(TransactionStatus.SUCCEEDED);
        assertThat(settledPayload().get("result").asString()).isEqualTo("REFUNDED");
    }

    @Test
    void unknownLookupStaysProcessingAndIsLookedUpAgain() {
        PaymentTransaction refund = sentThenRetryDue(PROVIDER_ERROR);
        given(toss.findByPaymentKey(paymentKey.value()))
                .willReturn(new TossLookupResult.Unknown(UnknownReason.TIMEOUT, null));

        worker.recoverDue();

        assertThat(statusOf(refund)).isEqualTo(TransactionStatus.RETRY_SCHEDULED);
        assertThat(lastErrorOf(refund).message()).isEqualTo("LOOKUP_TIMEOUT");
        makeRetryDue(refund.id());
        given(toss.findByPaymentKey(paymentKey.value())).willReturn(found(TossPaymentStatus.CANCELED));

        worker.recoverDue();

        verify(toss, never()).cancel(any(), any());
        assertThat(statusOf(refund)).isEqualTo(TransactionStatus.SUCCEEDED);
    }

    // ---- 거절 ----

    // 앞에 처리 중일 수 있는 키가 없다 — 거절은 조회로 미취소를 확인하면 그 사유로 확정한다. 키를 바꾸지 않는다
    @Test
    void rejectionConfirmedByLookupFailsWithItsReason(CapturedOutput output) {
        PaymentTransaction refund = refundOpened();
        given(toss.cancel(any(), any())).willReturn(new TossCommandResult.Rejected(NOT_CANCELABLE, "취소할 수 없는 결제"));
        given(toss.findByPaymentKey(paymentKey.value())).willReturn(found(TossPaymentStatus.DONE));

        worker.recoverDue();

        verify(toss, times(1)).cancel(any(), any());
        assertThat(statusOf(refund)).isEqualTo(TransactionStatus.FAILED);
        assertThat(lastErrorOf(refund).code()).isEqualTo(NOT_CANCELABLE);
        assertThat(keyOf(refund)).isEqualTo(refund.idempotencyKey().value());
        assertThat(payments.findPaymentByTarget(target).orElseThrow().status()).isEqualTo(PaymentStatus.SUCCEEDED);
        JsonNode settled = settledPayload();
        assertThat(settled.get("result").asString()).isEqualTo("FAILED");
        assertThat(settled.has("refundedAt") && !settled.get("refundedAt").isNull()).isFalse();
        assertThat(output.getAll()).contains("환불 실패 — 사람 확인 필요");
    }

    // 첫 전송 한참 뒤의 교체도 같다 — 교체는 앞 키가 일시 오류로 답했거나 불명 뒤 여유가 지났을 때만 일어나므로, 교체한 키의 거절은 결론이다
    @Test
    void rejectionAfterLateRotationFails() {
        PaymentTransaction refund = sentThenRetryDue(PROVIDER_ERROR);
        ageRequestedAt(refund.id(), Duration.ofHours(1));
        given(toss.findByPaymentKey(paymentKey.value())).willReturn(found(TossPaymentStatus.DONE));
        given(toss.cancel(any(), any())).willReturn(new TossCommandResult.Rejected(NOT_CANCELABLE, "취소할 수 없는 결제"));

        worker.recoverDue();

        assertThat(keyOf(refund)).isNotEqualTo(refund.idempotencyKey().value());
        assertThat(statusOf(refund)).isEqualTo(TransactionStatus.FAILED);
    }

    // 앞 회차가 거절을 받고 조회가 불명이라 남긴 거절 표시를 이어 받는다
    @Test
    void carriedRejectionFailsWithItsReason() {
        PaymentTransaction refund = sentThenRetryDue(new ProviderError(NOT_CANCELABLE, "LOOKUP_TIMEOUT"));
        given(toss.findByPaymentKey(paymentKey.value())).willReturn(found(TossPaymentStatus.DONE));

        worker.recoverDue();

        verify(toss, never()).cancel(any(), any());
        assertThat(statusOf(refund)).isEqualTo(TransactionStatus.FAILED);
        assertThat(lastErrorOf(refund).code()).isEqualTo(NOT_CANCELABLE);
    }

    @Test
    void rejectionWithCanceledLookupCompletes() {
        PaymentTransaction refund = refundOpened();
        given(toss.cancel(any(), any())).willReturn(new TossCommandResult.Rejected(NOT_CANCELABLE, "취소할 수 없는 결제"));
        given(toss.findByPaymentKey(paymentKey.value())).willReturn(found(TossPaymentStatus.CANCELED));

        worker.recoverDue();

        assertThat(statusOf(refund)).isEqualTo(TransactionStatus.SUCCEEDED);
    }

    @Test
    void unknownLookupAfterRejectionKeepsRejection() {
        PaymentTransaction refund = refundOpened();
        given(toss.cancel(any(), any())).willReturn(new TossCommandResult.Rejected(NOT_CANCELABLE, "취소할 수 없는 결제"));
        given(toss.findByPaymentKey(paymentKey.value()))
                .willReturn(new TossLookupResult.Unknown(UnknownReason.TIMEOUT, null));

        worker.recoverDue();

        assertThat(statusOf(refund)).isEqualTo(TransactionStatus.RETRY_SCHEDULED);
        assertThat(lastErrorOf(refund).code()).isEqualTo(NOT_CANCELABLE);
        makeRetryDue(refund.id());
        given(toss.findByPaymentKey(paymentKey.value())).willReturn(found(TossPaymentStatus.DONE));

        worker.recoverDue();

        verify(toss, times(1)).cancel(any(), any());
        assertThat(statusOf(refund)).isEqualTo(TransactionStatus.FAILED);
        assertThat(lastErrorOf(refund).code()).isEqualTo(NOT_CANCELABLE);
    }

    // ---- 에스컬레이션 ----

    @ParameterizedTest
    @EnumSource(value = TossPaymentStatus.class, names = {"PARTIAL_CANCELED", "UNRECOGNIZED", "WAITING_FOR_DEPOSIT",
            "READY", "IN_PROGRESS", "ABORTED", "EXPIRED"})
    void statesNotToAutoConfirmAreEscalated(TossPaymentStatus status) {
        PaymentTransaction refund = sentThenRetryDue(PROVIDER_ERROR);
        given(toss.findByPaymentKey(paymentKey.value())).willReturn(found(status));

        worker.recoverDue();

        assertEscalated(refund, "LOOKUP_" + status.name());
        assertThat(settledEvents()).isZero();
    }

    @Test
    void missingPaymentAtProviderIsEscalated() {
        PaymentTransaction refund = sentThenRetryDue(PROVIDER_ERROR);
        given(toss.findByPaymentKey(paymentKey.value())).willReturn(new TossLookupResult.NotFound("NOT_FOUND_PAYMENT"));

        worker.recoverDue();

        assertEscalated(refund, "LOOKUP_NOT_FOUND");
    }

    // 전액 취소인데 잔액이 남았거나 금액이 다르다 — 돈이 다르게 움직였을 수 있다
    @Test
    void refundResponseThatIsNotFullCancelIsEscalated() {
        PaymentTransaction refund = refundOpened();
        given(toss.cancel(any(), any())).willReturn(new TossCommandResult.Succeeded(new TossPayment(paymentKey.value(),
                "order", TossPaymentStatus.CANCELED, 15000, 5000, "카드", APPROVED_AT, "tx",
                List.of(new TossPayment.Cancel(CANCELED_AT, 10000)))));

        worker.recoverDue();

        assertEscalated(refund, RefundRecovery.REFUND_MISMATCH);
        assertThat(payments.findPaymentByTarget(target).orElseThrow().status()).isEqualTo(PaymentStatus.SUCCEEDED);
    }

    @Test
    void refundResponseWithoutCancelTimeIsEscalated() {
        PaymentTransaction refund = refundOpened();
        given(toss.cancel(any(), any())).willReturn(new TossCommandResult.Succeeded(new TossPayment(paymentKey.value(),
                "order", TossPaymentStatus.CANCELED, 15000, 0, "카드", APPROVED_AT, "tx", null)));

        worker.recoverDue();

        assertEscalated(refund, RefundRecovery.MISSING_CANCELED_AT);
    }

    @Test
    void unresolvedLongAfterFirstSendIsEscalatedWithoutCallingToss(CapturedOutput output) {
        PaymentTransaction refund = sentThenLeaseExpired(TIMEOUT);
        ageRequestedAt(refund.id(), RefundRecovery.GIVE_UP_AFTER.plusMinutes(1));

        worker.recoverDue();

        verify(toss, never()).cancel(any(), any());
        verify(toss, never()).findByPaymentKey(anyString());
        assertEscalated(refund, RefundRecovery.UNRESOLVED);
        assertThat(output.getAll()).contains("결제 복구 중단 — 사람 확인 필요");
    }

    // 승인 상한(1시간)보다 길다 — 몇 시간 이어지는 토스 장애에도 환불은 스스로 끝난다
    @Test
    void longOutageWithinDayIsStillRetried() {
        PaymentTransaction refund = sentThenLeaseExpired(TIMEOUT);
        ageRequestedAt(refund.id(), Duration.ofHours(5));
        given(toss.cancel(any(), any())).willReturn(canceled(AMOUNT));

        worker.recoverDue();

        assertThat(statusOf(refund)).isEqualTo(TransactionStatus.SUCCEEDED);
    }

    // ---- 간격 · 여러 인스턴스 ----

    @Test
    void retriesAreSpacedOutMoreWithEachAttempt() {
        PaymentTransaction refund = refundOpened();
        given(toss.cancel(any(), any())).willReturn(new TossCommandResult.Processing(BUSY.code()));

        worker.recoverDue();
        long first = secondsUntilRetry(refund.id());
        makeRetryDue(refund.id());
        worker.recoverDue();
        long second = secondsUntilRetry(refund.id());

        assertThat(first).isBetween(RefundRecovery.FIRST_RETRY_AFTER.toSeconds() - 5,
                RefundRecovery.FIRST_RETRY_AFTER.toSeconds());
        assertThat(second).isBetween(RefundRecovery.FIRST_RETRY_AFTER.toSeconds() * 2 - 5,
                RefundRecovery.FIRST_RETRY_AFTER.toSeconds() * 2);
    }

    @Test
    void retryIntervalIsCapped() {
        PaymentTransaction refund = claim(refundOpened()).transaction();
        jdbcTemplate.update("UPDATE payment_transactions SET attempt_count = 40 WHERE id = ?", refund.id());
        PaymentTransaction many = transactions.findById(refund.id()).orElseThrow();

        assertThat(RefundRecovery.retryAfter(many)).isEqualTo(RefundRecovery.MAX_RETRY_AFTER);
    }

    // 리스(70초)가 끝난 뒤 늦게 온 결과는 반영하지 않는다 — 다른 작업자가 같은 키로 이어 받아 확정한다
    @Test
    void lateResultAfterLeaseExpiryIsNotApplied() {
        ClaimedTransaction sent = claim(refundOpened());
        expireLease(sent.transaction().id());

        assertThatThrownBy(() -> settlement.settle(sent, new Outcome.Confirmed(CANCELED_AT)))
                .isInstanceOf(LeaseLostException.class);

        assertThat(statusOf(sent.transaction())).isEqualTo(TransactionStatus.PROCESSING);
        assertThat(payments.findPaymentByTarget(target).orElseThrow().status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(settledEvents()).isZero();
    }

    // 인스턴스 둘이 동시에 돌아도 환불마다 토스 취소는 한 번이다(SKIP LOCKED + 리스)
    @Test
    void concurrentWorkersSendEachRefundOnce() throws Exception {
        List<PaymentTransaction> refunds = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            target = PaymentFixtures.newOrderTarget();
            paymentKey = PaymentFixtures.newProviderPayment();
            refunds.add(refundOpened());
        }
        Map<String, AtomicInteger> sent = new ConcurrentHashMap<>();
        given(toss.cancel(any(), any())).willAnswer(invocation -> {
            TossCancelRequest request = invocation.getArgument(0);
            sent.computeIfAbsent(request.paymentKey(), k -> new AtomicInteger()).incrementAndGet();
            return new TossCommandResult.Succeeded(new TossPayment(request.paymentKey(), "order",
                    TossPaymentStatus.CANCELED, 15000, 0, "카드", APPROVED_AT, "tx",
                    List.of(new TossPayment.Cancel(CANCELED_AT, 15000))));
        });

        List<Concurrently.Outcome<Integer>> outcomes = Concurrently.run(2, i -> () -> worker.recoverDue());

        assertThat(outcomes).allSatisfy(o -> assertThat(o.error()).isNull());
        assertThat(outcomes.stream().mapToInt(Concurrently.Outcome::value).sum()).isEqualTo(refunds.size());
        for (PaymentTransaction refund : refunds) {
            assertThat(sent.get(refund.providerPaymentKey().value())).hasValue(1);
            assertThat(statusOf(refund)).isEqualTo(TransactionStatus.SUCCEEDED);
        }
    }

    // ---- 준비 ----

    /** 승인된 결제에 환불을 연다(PENDING, 아직 보내지 않음). */
    private PaymentTransaction refundOpened() {
        PaymentTransaction pending = transactionTemplate.execute(s -> ledger.openCapture(target, AMOUNT));
        ClaimedTransaction started = transactionTemplate.execute(s -> ledger.start(pending, target, paymentKey, AMOUNT))
                .orElseThrow();
        transactionTemplate.executeWithoutResult(s -> ledger.resolve(started, new Outcome.Confirmed(APPROVED_AT)));
        return transactionTemplate.execute(s -> ledger.openRefund(target));
    }

    /** 환불을 한 번 보낸 뒤 결과 불명(error) 또는 반영 전 멈춤(null)으로 남기고 리스를 끝낸다. */
    private PaymentTransaction sentThenLeaseExpired(ProviderError error) {
        ClaimedTransaction sent = claim(refundOpened());
        if (error != null) {
            transactionTemplate.executeWithoutResult(s -> ledger.resolve(sent, new Outcome.Unknown(error)));
        }
        expireLease(sent.transaction().id());
        return sent.transaction();
    }

    private PaymentTransaction sentThenRetryDue(ProviderError error) {
        ClaimedTransaction sent = claim(refundOpened());
        transactionTemplate.executeWithoutResult(s ->
                ledger.resolve(sent, new Outcome.InProgress(error, Duration.ofSeconds(10))));
        makeRetryDue(sent.transaction().id());
        return sent.transaction();
    }

    private ClaimedTransaction claim(PaymentTransaction refund) {
        return transactionTemplate.execute(s -> ledger.claim(refund)).orElseThrow();
    }

    private TossCommandResult canceled(Money amount) {
        long won = amount.amount().longValueExact();
        return new TossCommandResult.Succeeded(new TossPayment(paymentKey.value(), "order", TossPaymentStatus.CANCELED,
                won, 0, "카드", APPROVED_AT, "tx", List.of(new TossPayment.Cancel(CANCELED_AT, won))));
    }

    private TossLookupResult found(TossPaymentStatus status) {
        long won = AMOUNT.amount().longValueExact();
        boolean canceled = status == TossPaymentStatus.CANCELED;
        return new TossLookupResult.Found(new TossPayment(paymentKey.value(), "order", status, won,
                canceled ? 0 : won, "카드", APPROVED_AT, "tx",
                canceled ? List.of(new TossPayment.Cancel(CANCELED_AT, won)) : null));
    }

    // ---- 확인 ----

    private void assertEscalated(PaymentTransaction transaction, String code) {
        PaymentTransaction after = transactions.findById(transaction.id()).orElseThrow();
        assertThat(after.isEscalated()).isTrue();
        assertThat(after.status()).as("에스컬레이션은 실패로 굳히지 않는다").isEqualTo(TransactionStatus.PROCESSING);
        assertThat(after.lastError().code()).isEqualTo(code);
    }

    private TransactionStatus statusOf(PaymentTransaction transaction) {
        return transactions.findById(transaction.id()).orElseThrow().status();
    }

    private String keyOf(PaymentTransaction transaction) {
        return transactions.findById(transaction.id()).orElseThrow().idempotencyKey().value();
    }

    private ProviderError lastErrorOf(PaymentTransaction transaction) {
        return transactions.findById(transaction.id()).orElseThrow().lastError();
    }

    private int settledEvents() {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM payment_outbox_events
                 WHERE aggregate_type = 'ORDER' AND aggregate_id = ? AND event_type = 'ORDER_REFUND_SETTLED'
                """, Integer.class, target.id());
    }

    private JsonNode settledPayload() {
        List<String> payloads = jdbcTemplate.queryForList("""
                SELECT payload FROM payment_outbox_events
                 WHERE aggregate_type = 'ORDER' AND aggregate_id = ? AND event_type = 'ORDER_REFUND_SETTLED'
                """, String.class, target.id());
        assertThat(payloads).hasSize(1);
        assertThat(payloads.getFirst()).doesNotContain(paymentKey.value());
        return jsonMapper.readTree(payloads.getFirst());
    }

    private long secondsUntilRetry(Long id) {
        return jdbcTemplate.queryForObject(
                "SELECT TIMESTAMPDIFF(SECOND, UTC_TIMESTAMP(6), next_retry_at) FROM payment_transactions WHERE id = ?",
                Long.class, id);
    }

    // ---- 시각 당기기 ----

    private void expireLease(Long id) {
        jdbcTemplate.update("UPDATE payment_transactions SET lease_expires_at = UTC_TIMESTAMP(6) - INTERVAL 1 SECOND "
                + "WHERE id = ?", id);
    }

    private void makeRetryDue(Long id) {
        jdbcTemplate.update("UPDATE payment_transactions SET next_retry_at = UTC_TIMESTAMP(6) - INTERVAL 1 SECOND "
                + "WHERE id = ?", id);
    }

    private void ageRequestedAt(Long id, Duration age) {
        jdbcTemplate.update("UPDATE payment_transactions SET requested_at = requested_at - INTERVAL ? SECOND WHERE id = ?",
                age.toSeconds(), id);
    }
}
