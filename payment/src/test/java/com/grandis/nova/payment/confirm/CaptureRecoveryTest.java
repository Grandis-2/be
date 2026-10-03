package com.grandis.nova.payment.confirm;

import com.grandis.nova.common.security.RevocationChecker;
import com.grandis.nova.common.testing.Concurrently;
import com.grandis.nova.payment.ClaimedTransaction;
import com.grandis.nova.payment.PaymentLedger;
import com.grandis.nova.payment.client.toss.TossCommandResult;
import com.grandis.nova.payment.client.toss.TossConfirmRequest;
import com.grandis.nova.payment.client.toss.TossIdempotencyKey;
import com.grandis.nova.payment.client.toss.TossLookupResult;
import com.grandis.nova.payment.client.toss.TossPayment;
import com.grandis.nova.payment.client.toss.TossPaymentClient;
import com.grandis.nova.payment.client.toss.TossPaymentStatus;
import com.grandis.nova.payment.client.toss.UnknownReason;
import com.grandis.nova.payment.domain.enums.TransactionStatus;
import com.grandis.nova.payment.domain.model.Outcome;
import com.grandis.nova.payment.domain.model.PaymentTransaction;
import com.grandis.nova.payment.domain.repository.PaymentTransactionReader;
import com.grandis.nova.payment.recovery.RecoveryWorker;
import com.grandis.nova.payment.support.PaymentFixtures;
import com.grandis.nova.payment.support.PaymentIntegrationTest;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 결과 불명 복구 — 에픽 계획서 "결과 불명 해소 2단계 계약" 표를 여기 고정한다. MySQL 위에서 돌고 토스 클라이언트만 대역이다
 * (HTTP → 분류는 TossPaymentClientTest). 거래는 원장으로 만들고 리스 만료 · 재시도 시각 도래 · 승인 창 경과는 행의 시각을 당겨 만든다.
 * 통합 테스트는 커밋된 행을 공유하므로, 워커를 돌리기 전에 다른 테스트가 남긴 후보를 에스컬레이션해 둔다.
 */
@PaymentIntegrationTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class CaptureRecoveryTest {

    static final Money AMOUNT = Money.won(15000);
    static final Instant APPROVED_AT = Instant.parse("2026-10-03T03:04:05Z");
    static final ProviderError TIMEOUT = new ProviderError(UnknownReason.TIMEOUT.name(), UnknownReason.TIMEOUT.name());
    static final ProviderError UNLISTED = new ProviderError("SOMETHING_NEW", UnknownReason.UNLISTED_CODE.name());
    static final ProviderError PROVIDER_ERROR = new ProviderError("PROVIDER_ERROR", "일시적인 오류");
    static final ProviderError BUSY = new ProviderError("IDEMPOTENT_REQUEST_PROCESSING", null);
    static final Duration PAST_SETTLE_DEADLINE =
            CaptureRecovery.APPROVAL_WINDOW.plus(CaptureRecovery.SETTLE_GRACE).plusMinutes(1);

    @Autowired
    RecoveryWorker worker;

    @Autowired
    PaymentLedger ledger;

    @Autowired
    PaymentTransactionReader transactions;

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
        parkOtherRecoverables();
    }

    // ---- 1단계: 같은 키 재전송 ----

    // 성공 응답을 잃었다(반영 전에 멈춤 — 마지막 오류 없음) → 같은 키 재전송이 첫 요청의 성공을 돌려준다
    @Test
    void lostSuccessIsConfirmedByResendingWithSameKey(CapturedOutput output) {
        PaymentTransaction lost = startedThenLeaseExpired(null);
        given(toss.confirm(any(), any())).willReturn(done(lost, AMOUNT));

        worker.recoverDue();

        verify(toss).confirm(new TossConfirmRequest(paymentKey.value(), lost.providerOrderId().value(), 15000),
                new TossIdempotencyKey(lost.idempotencyKey().value()));
        verify(toss, never()).findByPaymentKey(anyString());
        assertThat(statusOf(lost)).isEqualTo(TransactionStatus.SUCCEEDED);
        assertThat(paymentsOfTarget()).isEqualTo(1);
        JsonNode settled = settledPayload();
        assertThat(settled.get("result").asString()).isEqualTo("APPROVED");
        assertThat(settled.get("providerOrderId").asString()).isEqualTo(lost.providerOrderId().value());
        assertThat(output.getAll()).doesNotContain(paymentKey.value());
    }

    @Test
    void timeoutIsResentWithSameKey() {
        PaymentTransaction timedOut = startedThenLeaseExpired(TIMEOUT);
        given(toss.confirm(any(), any())).willReturn(done(timedOut, AMOUNT));

        worker.recoverDue();

        verify(toss).confirm(any(), any(TossIdempotencyKey.class));
        verify(toss, never()).findByPaymentKey(anyString());
        assertThat(keyOf(timedOut)).isEqualTo(timedOut.idempotencyKey().value());
        assertThat(statusOf(timedOut)).isEqualTo(TransactionStatus.SUCCEEDED);
    }

    // 409(같은 요청 처리 중) → 재시도 예약. 다음 재전송도 같은 키다 — 토스가 첫 요청의 결과를 돌려준다
    @Test
    void processingIsRescheduledAndResentWithSameKey() {
        PaymentTransaction lost = startedThenLeaseExpired(null);
        given(toss.confirm(any(), any())).willReturn(new TossCommandResult.Processing("IDEMPOTENT_REQUEST_PROCESSING"));

        worker.recoverDue();
        assertThat(statusOf(lost)).isEqualTo(TransactionStatus.RETRY_SCHEDULED);

        makeRetryDue(lost.id());
        given(toss.confirm(any(), any())).willReturn(done(lost, AMOUNT));
        worker.recoverDue();

        verify(toss, times(2)).confirm(any(), any());
        verify(toss, never()).findByPaymentKey(anyString());
        assertThat(keyOf(lost)).isEqualTo(lost.idempotencyKey().value());
        assertThat(statusOf(lost)).isEqualTo(TransactionStatus.SUCCEEDED);
    }

    // ---- 1단계: 조회 ----

    @Test
    void unreadableAnswerIsLookedUpNotResent() {
        PaymentTransaction unknown = startedThenLeaseExpired(UNLISTED);
        given(toss.findByPaymentKey(paymentKey.value())).willReturn(found(unknown, TossPaymentStatus.DONE, AMOUNT));

        worker.recoverDue();

        verify(toss, never()).confirm(any(), any());
        assertThat(statusOf(unknown)).isEqualTo(TransactionStatus.SUCCEEDED);
        assertThat(settledPayload().get("result").asString()).isEqualTo("APPROVED");
    }

    // 토스는 오류 응답도 멱등 키에 캐시한다 — 일시 오류 뒤 같은 키로는 같은 오류만 온다. 조회로 미처리를 확인하고 키를 바꿔 보낸다
    @Test
    void transientErrorIsLookedUpAndUnprocessedIsResentWithNewKey() {
        PaymentTransaction retry = startedThenRetryDue(PROVIDER_ERROR);
        given(toss.findByPaymentKey(paymentKey.value()))
                .willReturn(found(retry, TossPaymentStatus.IN_PROGRESS, AMOUNT));
        given(toss.confirm(any(), any())).willReturn(done(retry, AMOUNT));

        worker.recoverDue();

        String rotated = keyOf(retry);
        assertThat(rotated).isNotEqualTo(retry.idempotencyKey().value());
        verify(toss).confirm(any(), eq(new TossIdempotencyKey(rotated)));
        assertThat(statusOf(retry)).isEqualTo(TransactionStatus.SUCCEEDED);
    }

    // ---- 2단계: 조회 상태별 다음 행동 ----

    @Test
    void lookedUpDoneWithOtherAmountIsEscalatedNotConfirmed(CapturedOutput output) {
        PaymentTransaction unknown = startedThenLeaseExpired(UNLISTED);
        given(toss.findByPaymentKey(paymentKey.value())).willReturn(found(unknown, TossPaymentStatus.DONE, Money.won(1)));

        worker.recoverDue();

        assertEscalated(unknown, TossOutcomes.AMOUNT_MISMATCH);
        assertThat(paymentsOfTarget()).isZero();
        assertThat(output.getAll()).contains("사람 확인 필요");
    }

    @Test
    void lookedUpAbortedFails() {
        PaymentTransaction unknown = startedThenLeaseExpired(UNLISTED);
        given(toss.findByPaymentKey(paymentKey.value())).willReturn(found(unknown, TossPaymentStatus.ABORTED, AMOUNT));

        worker.recoverDue();

        assertThat(statusOf(unknown)).isEqualTo(TransactionStatus.FAILED);
        JsonNode settled = settledPayload();
        assertThat(settled.get("result").asString()).isEqualTo("DECLINED");
        assertThat(settled.get("declineReason").asString()).isEqualTo("FAILED");
    }

    @Test
    void lookedUpExpiredFailsAsExpiredPayment() {
        PaymentTransaction unknown = startedThenLeaseExpired(UNLISTED);
        given(toss.findByPaymentKey(paymentKey.value())).willReturn(found(unknown, TossPaymentStatus.EXPIRED, AMOUNT));

        worker.recoverDue();

        assertThat(statusOf(unknown)).isEqualTo(TransactionStatus.FAILED);
        assertThat(settledPayload().get("declineReason").asString()).isEqualTo("PAYMENT_EXPIRED");
    }

    @ParameterizedTest
    @EnumSource(value = TossPaymentStatus.class, names = {"READY", "IN_PROGRESS"})
    void unprocessedAfterSettleDeadlineFailsWithoutResending(TossPaymentStatus status) {
        PaymentTransaction unknown = startedThenLeaseExpired(UNLISTED);
        ageRequestedAt(unknown.id(), PAST_SETTLE_DEADLINE);
        given(toss.findByPaymentKey(paymentKey.value())).willReturn(found(unknown, status, AMOUNT));

        worker.recoverDue();

        verify(toss, never()).confirm(any(), any());
        assertThat(statusOf(unknown)).isEqualTo(TransactionStatus.FAILED);
        assertThat(settledPayload().get("declineReason").asString()).isEqualTo("PAYMENT_EXPIRED");
    }

    /*
     * 리뷰 High: 창 끝 직전에 보낸 요청이 토스 안에서 아직 처리 중일 수 있다. 창이 닫히자마자 미처리를 실패로 확정하면, 그 뒤 토스가
     * 승인했을 때 청구됐는데 FAILED · 주문은 결제 대기가 된다(이중 청구로 이어진다). 정산 기한까지는 다시 조회만 한다.
     */
    @ParameterizedTest
    @EnumSource(value = TossPaymentStatus.class, names = {"READY", "IN_PROGRESS"})
    void unprocessedRightAfterWindowIsRecheckedNotFailed(TossPaymentStatus status) {
        PaymentTransaction unknown = startedThenLeaseExpired(UNLISTED);
        ageRequestedAt(unknown.id(), CaptureRecovery.APPROVAL_WINDOW.plusMinutes(1));
        given(toss.findByPaymentKey(paymentKey.value())).willReturn(found(unknown, status, AMOUNT));

        worker.recoverDue();

        verify(toss, never()).confirm(any(), any());
        assertThat(statusOf(unknown)).isEqualTo(TransactionStatus.RETRY_SCHEDULED);
        assertThat(keyOf(unknown)).isEqualTo(unknown.idempotencyKey().value());
        assertThat(settledEvents()).isZero();

        makeRetryDue(unknown.id());
        given(toss.findByPaymentKey(paymentKey.value())).willReturn(found(unknown, TossPaymentStatus.DONE, AMOUNT));
        worker.recoverDue();

        assertThat(statusOf(unknown)).isEqualTo(TransactionStatus.SUCCEEDED);
    }

    // 응답을 못 받은 요청은 창 밖에서도 같은 키로 다시 보낸다 — 무해하다(처리됐으면 그 결과, 안 됐으면 토스가 세션 만료로 거절)
    @Test
    void timeoutAfterWindowIsResentWithSameKey() {
        PaymentTransaction timedOut = startedThenLeaseExpired(TIMEOUT);
        ageRequestedAt(timedOut.id(), PAST_SETTLE_DEADLINE);
        given(toss.confirm(any(), any())).willReturn(done(timedOut, AMOUNT));

        worker.recoverDue();

        verify(toss).confirm(any(), eq(new TossIdempotencyKey(timedOut.idempotencyKey().value())));
        verify(toss, never()).findByPaymentKey(anyString());
        assertThat(statusOf(timedOut)).isEqualTo(TransactionStatus.SUCCEEDED);
    }

    /*
     * 결제 키 조회 404: 미처리와 같은 규칙이다. 창 안이면 새 키로 한 번 더 보낸다(에픽 계약 "재전송 1회"). 그 거절도 조회가 여전히
     * 없음이면 정산 기한까지 다시 조회만 하고(처리 중인 결제가 404 로 보여도 실패로 굳히지 않게), 닫힌 뒤 그 거절 사유로 확정한다.
     */
    @Test
    void notFoundIsResentOnceWithNewKeyAndFailsOnlyAfterSettleDeadline() {
        PaymentTransaction unknown = startedThenLeaseExpired(UNLISTED);
        given(toss.findByPaymentKey(paymentKey.value())).willReturn(new TossLookupResult.NotFound("NOT_FOUND_PAYMENT"));
        given(toss.confirm(any(), any()))
                .willReturn(new TossCommandResult.Rejected("NOT_FOUND_PAYMENT_SESSION", "결제 시간이 만료되었습니다"));

        worker.recoverDue();
        assertThat(keyOf(unknown)).isNotEqualTo(unknown.idempotencyKey().value());
        assertThat(statusOf(unknown)).isEqualTo(TransactionStatus.RETRY_SCHEDULED);
        assertThat(settledEvents()).isZero();

        ageRequestedAt(unknown.id(), PAST_SETTLE_DEADLINE);
        makeRetryDue(unknown.id());
        worker.recoverDue();

        verify(toss, times(1)).confirm(any(), any());
        assertThat(statusOf(unknown)).isEqualTo(TransactionStatus.FAILED);
        assertThat(transactions.findById(unknown.id()).orElseThrow().lastError().code())
                .isEqualTo("NOT_FOUND_PAYMENT_SESSION");
        assertThat(settledPayload().get("declineReason").asString()).isEqualTo("PAYMENT_EXPIRED");
    }

    @Test
    void notFoundAfterSettleDeadlineFails() {
        PaymentTransaction unknown = startedThenLeaseExpired(UNLISTED);
        ageRequestedAt(unknown.id(), PAST_SETTLE_DEADLINE);
        given(toss.findByPaymentKey(paymentKey.value())).willReturn(new TossLookupResult.NotFound("NOT_FOUND_PAYMENT"));

        worker.recoverDue();

        verify(toss, never()).confirm(any(), any());
        assertThat(statusOf(unknown)).isEqualTo(TransactionStatus.FAILED);
    }

    // 자동 확정하지 않는 상태: 사람이 본다. 상태는 그대로(실패로 굳히지 않음), 알림 없음, 다시 집지 않음
    @ParameterizedTest
    @EnumSource(value = TossPaymentStatus.class,
            names = {"CANCELED", "PARTIAL_CANCELED", "WAITING_FOR_DEPOSIT", "UNRECOGNIZED"})
    void statesNotToAutoConfirmAreEscalated(TossPaymentStatus status) {
        PaymentTransaction unknown = startedThenLeaseExpired(UNLISTED);
        given(toss.findByPaymentKey(paymentKey.value())).willReturn(found(unknown, status, AMOUNT));

        worker.recoverDue();
        expireLease(unknown.id());
        worker.recoverDue();

        assertEscalated(unknown, TossOutcomes.LOOKUP + status.name());
        verify(toss).findByPaymentKey(anyString());
        assertThat(settledEvents()).isZero();
    }

    @Test
    void lookedUpPaymentOfAnotherOrderIsEscalated() {
        PaymentTransaction unknown = startedThenLeaseExpired(UNLISTED);
        given(toss.findByPaymentKey(paymentKey.value())).willReturn(new TossLookupResult.Found(new TossPayment(
                paymentKey.value(), "another-order-id", TossPaymentStatus.DONE, 15000, 15000, "카드", APPROVED_AT, "tx")));

        worker.recoverDue();

        assertEscalated(unknown, CaptureRecovery.ORDER_MISMATCH);
        assertThat(paymentsOfTarget()).isZero();
    }

    // 조회도 불명이면 그대로 두고 다음에 다시 조회한다(재전송으로 바뀌지 않는다)
    @Test
    void unknownLookupStaysProcessingAndIsLookedUpAgain() {
        PaymentTransaction unknown = startedThenLeaseExpired(UNLISTED);
        given(toss.findByPaymentKey(paymentKey.value()))
                .willReturn(new TossLookupResult.Unknown(UnknownReason.TIMEOUT, null))
                .willReturn(found(unknown, TossPaymentStatus.DONE, AMOUNT));

        worker.recoverDue();
        PaymentTransaction after = transactions.findById(unknown.id()).orElseThrow();
        assertThat(after.status()).isEqualTo(TransactionStatus.PROCESSING);
        assertThat(after.lastError().code()).isEqualTo(TossOutcomes.LOOKUP + UnknownReason.TIMEOUT.name());

        expireLease(unknown.id());
        worker.recoverDue();

        verify(toss, never()).confirm(any(), any());
        verify(toss, times(2)).findByPaymentKey(paymentKey.value());
        assertThat(statusOf(unknown)).isEqualTo(TransactionStatus.SUCCEEDED);
    }

    /*
     * 리뷰(2차) Medium: 새 키가 거절됐는데 조회는 아직 미처리다 — 앞선 키의 요청이 토스 안에서 처리 중일 수 있다(그 요청이 승인하면
     * 청구된다). 거절로 확정하지 않고, 키도 다시 바꾸지 않고 다시 조회한다.
     */
    @Test
    void rejectionOfNewKeyWhileStillInProgressIsRecheckedNotFailed() {
        PaymentTransaction retry = startedThenRetryDue(PROVIDER_ERROR);
        given(toss.findByPaymentKey(paymentKey.value())).willReturn(found(retry, TossPaymentStatus.IN_PROGRESS, AMOUNT));
        given(toss.confirm(any(), any()))
                .willReturn(new TossCommandResult.Rejected("NOT_FOUND_PAYMENT_SESSION", "결제 시간이 만료되었습니다"));

        worker.recoverDue();

        verify(toss, times(1)).confirm(any(), any());
        assertThat(statusOf(retry)).isEqualTo(TransactionStatus.RETRY_SCHEDULED);
        assertThat(settledEvents()).isZero();
    }

    // 같은 키 재전송의 거절도 같은 규칙이다 — 정산 중에 받은 세션 만료 거절은 조회가 미처리면 실패로 확정하지 않는다
    @Test
    void rejectionWhileSettlingIsRecheckedNotFailed() {
        PaymentTransaction timedOut = startedThenLeaseExpired(TIMEOUT);
        ageRequestedAt(timedOut.id(), CaptureRecovery.APPROVAL_WINDOW.plusMinutes(1));
        given(toss.confirm(any(), any()))
                .willReturn(new TossCommandResult.Rejected("NOT_FOUND_PAYMENT_SESSION", "결제 시간이 만료되었습니다"));
        given(toss.findByPaymentKey(paymentKey.value()))
                .willReturn(found(timedOut, TossPaymentStatus.IN_PROGRESS, AMOUNT));

        worker.recoverDue();

        assertThat(statusOf(timedOut)).isEqualTo(TransactionStatus.RETRY_SCHEDULED);
        assertThat(settledEvents()).isZero();
    }

    // 거절을 확인하는 조회도 자동 확정 금지 상태면 에스컬레이션이다(일부 취소 = 일부 청구됨)
    @Test
    void rejectionWithPartiallyCanceledLookupIsEscalated() {
        PaymentTransaction retry = startedThenRetryDue(PROVIDER_ERROR);
        given(toss.findByPaymentKey(paymentKey.value()))
                .willReturn(found(retry, TossPaymentStatus.IN_PROGRESS, AMOUNT))
                .willReturn(found(retry, TossPaymentStatus.PARTIAL_CANCELED, AMOUNT));
        given(toss.confirm(any(), any())).willReturn(new TossCommandResult.Rejected("REJECT_CARD_PAYMENT", "한도 초과"));

        worker.recoverDue();

        assertEscalated(retry, TossOutcomes.LOOKUP + "PARTIAL_CANCELED");
        assertThat(settledEvents()).isZero();
    }

    // 거절이 확정되는 길: 조회도 승인 · 미처리가 아니다(ABORTED) — 받은 거절의 사유로 알린다
    @Test
    void rejectionConfirmedByLookupFailsWithItsReason() {
        PaymentTransaction lost = startedThenLeaseExpired(null);
        given(toss.confirm(any(), any())).willReturn(new TossCommandResult.Rejected("REJECT_CARD_PAYMENT", "한도 초과"));
        given(toss.findByPaymentKey(paymentKey.value())).willReturn(found(lost, TossPaymentStatus.ABORTED, AMOUNT));

        worker.recoverDue();

        verify(toss).findByPaymentKey(paymentKey.value());
        assertThat(statusOf(lost)).isEqualTo(TransactionStatus.FAILED);
        assertThat(transactions.findById(lost.id()).orElseThrow().lastError().code()).isEqualTo("REJECT_CARD_PAYMENT");
        assertThat(settledPayload().get("declineReason").asString()).isEqualTo("CARD_REJECTED");
    }

    // 리뷰(3차) Low: 받은 거절은 행에 남는다 — 다음 회차도 키를 바꾸지 않고, 닫힌 뒤 그 거절의 사유로 확정한다
    @Test
    void rejectionCarriesOverRechecksUntilClosed() {
        PaymentTransaction retry = startedThenRetryDue(PROVIDER_ERROR);
        given(toss.findByPaymentKey(paymentKey.value())).willReturn(found(retry, TossPaymentStatus.IN_PROGRESS, AMOUNT));
        given(toss.confirm(any(), any())).willReturn(new TossCommandResult.Rejected("REJECT_CARD_PAYMENT", "한도 초과"));

        worker.recoverDue();
        assertThat(transactions.findById(retry.id()).orElseThrow().lastError().code()).isEqualTo("REJECT_CARD_PAYMENT");
        String keyAfterFirst = keyOf(retry);
        makeRetryDue(retry.id());
        worker.recoverDue();

        verify(toss, times(1)).confirm(any(), any());
        assertThat(keyOf(retry)).isEqualTo(keyAfterFirst);
        assertThat(statusOf(retry)).isEqualTo(TransactionStatus.RETRY_SCHEDULED);

        ageRequestedAt(retry.id(), PAST_SETTLE_DEADLINE);
        makeRetryDue(retry.id());
        worker.recoverDue();

        assertThat(statusOf(retry)).isEqualTo(TransactionStatus.FAILED);
        assertThat(settledPayload().get("declineReason").asString()).isEqualTo("CARD_REJECTED");
    }

    // 거절을 확인하는 조회가 불명이어도 받은 거절 표시는 남는다 — 다음 회차가 키를 또 바꾸지 않는다
    @Test
    void unknownLookupAfterRejectionKeepsRejection() {
        PaymentTransaction retry = startedThenRetryDue(PROVIDER_ERROR);
        given(toss.findByPaymentKey(paymentKey.value()))
                .willReturn(found(retry, TossPaymentStatus.IN_PROGRESS, AMOUNT))
                .willReturn(new TossLookupResult.Unknown(UnknownReason.TIMEOUT, null))
                .willReturn(found(retry, TossPaymentStatus.IN_PROGRESS, AMOUNT));
        given(toss.confirm(any(), any())).willReturn(new TossCommandResult.Rejected("REJECT_CARD_PAYMENT", "한도 초과"));

        worker.recoverDue();
        assertThat(transactions.findById(retry.id()).orElseThrow().lastError().code()).isEqualTo("REJECT_CARD_PAYMENT");
        String keyAfterFirst = keyOf(retry);
        expireLease(retry.id());
        worker.recoverDue();

        verify(toss, times(1)).confirm(any(), any());
        assertThat(keyOf(retry)).isEqualTo(keyAfterFirst);
        assertThat(statusOf(retry)).isEqualTo(TransactionStatus.RETRY_SCHEDULED);
    }

    @Test
    void rejectionWhileSettlingWithNotFoundIsRechecked() {
        PaymentTransaction timedOut = startedThenLeaseExpired(TIMEOUT);
        ageRequestedAt(timedOut.id(), CaptureRecovery.APPROVAL_WINDOW.plusMinutes(1));
        given(toss.confirm(any(), any()))
                .willReturn(new TossCommandResult.Rejected("NOT_FOUND_PAYMENT_SESSION", "결제 시간이 만료되었습니다"));
        given(toss.findByPaymentKey(paymentKey.value())).willReturn(new TossLookupResult.NotFound("NOT_FOUND_PAYMENT"));

        worker.recoverDue();

        assertThat(statusOf(timedOut)).isEqualTo(TransactionStatus.RETRY_SCHEDULED);
        assertThat(settledEvents()).isZero();
    }

    // 리뷰(2차) Low: 조회 불명 한 번에 "키 교체 금지" 표시가 사라지지 않는다
    @Test
    void unknownLookupKeepsDoNotRotateMark() {
        PaymentTransaction busy = startedThenRetryDue(new ProviderError("ALREADY_PROCESSING_REQUEST", "이미 처리 중"));
        given(toss.findByPaymentKey(paymentKey.value()))
                .willReturn(new TossLookupResult.Unknown(UnknownReason.TIMEOUT, null))
                .willReturn(found(busy, TossPaymentStatus.IN_PROGRESS, AMOUNT));

        worker.recoverDue();
        assertThat(transactions.findById(busy.id()).orElseThrow().lastError().code())
                .isEqualTo("ALREADY_PROCESSING_REQUEST");
        expireLease(busy.id());
        worker.recoverDue();

        verify(toss, never()).confirm(any(), any());
        assertThat(keyOf(busy)).isEqualTo(busy.idempotencyKey().value());
        assertThat(statusOf(busy)).isEqualTo(TransactionStatus.RETRY_SCHEDULED);
    }

    // 리뷰(2차) Low: 복구의 처리 중 · 일시 오류는 30초 뒤에 다시 본다(같은 키 재전송 · 키 교체가 10초마다 몰리지 않게)
    @Test
    void retriesFromRecoveryAreSpacedOut() {
        PaymentTransaction lost = startedThenLeaseExpired(null);
        given(toss.confirm(any(), any())).willReturn(new TossCommandResult.Processing("IDEMPOTENT_REQUEST_PROCESSING"));

        worker.recoverDue();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT TIMESTAMPDIFF(SECOND, UTC_TIMESTAMP(6), next_retry_at) FROM payment_transactions WHERE id = ?",
                Long.class, lost.id())).isBetween(CaptureRecovery.RECHECK_AFTER.toSeconds() - 5,
                CaptureRecovery.RECHECK_AFTER.toSeconds());
    }

    // ---- 상한 · 리스 · 여러 인스턴스 ----

    @Test
    void unresolvedLongAfterFirstSendIsEscalatedWithoutCallingToss() {
        PaymentTransaction unknown = startedThenLeaseExpired(UNLISTED);
        ageRequestedAt(unknown.id(), CaptureRecovery.GIVE_UP_AFTER.plusMinutes(1));

        worker.recoverDue();

        verify(toss, never()).confirm(any(), any());
        verify(toss, never()).findByPaymentKey(anyString());
        assertEscalated(unknown, CaptureRecovery.UNRESOLVED);
    }

    // 리뷰 High: 상한은 시각이다 — 창 안에서 일시 오류가 이어져 보낸 횟수가 많아도 스스로 끝나는 반복이라 에스컬레이션하지 않는다
    @Test
    void manyAttemptsInsideWindowAreNotEscalated() {
        PaymentTransaction retry = startedThenRetryDue(PROVIDER_ERROR);
        jdbcTemplate.update("UPDATE payment_transactions SET attempt_count = 40 WHERE id = ?", retry.id());
        given(toss.findByPaymentKey(paymentKey.value())).willReturn(found(retry, TossPaymentStatus.IN_PROGRESS, AMOUNT));
        given(toss.confirm(any(), any())).willReturn(new TossCommandResult.Transient("PROVIDER_ERROR", "일시 오류"));

        worker.recoverDue();

        PaymentTransaction after = transactions.findById(retry.id()).orElseThrow();
        assertThat(after.isEscalated()).isFalse();
        assertThat(after.status()).isEqualTo(TransactionStatus.RETRY_SCHEDULED);
    }

    // 리뷰 Medium: 결제 단위 "처리 중"은 이 키에 캐시된다 — 같은 키로는 끝나지 않고, 새 키는 진행 중인 요청과 겹친다. 조회로 기다린다
    @Test
    void paymentLevelProcessingIsLookedUpWithoutRotating() {
        PaymentTransaction busy = startedThenRetryDue(new ProviderError("ALREADY_PROCESSING_REQUEST", "이미 처리 중"));
        given(toss.findByPaymentKey(paymentKey.value()))
                .willReturn(found(busy, TossPaymentStatus.IN_PROGRESS, AMOUNT))
                .willReturn(found(busy, TossPaymentStatus.DONE, AMOUNT));

        worker.recoverDue();
        assertThat(statusOf(busy)).isEqualTo(TransactionStatus.RETRY_SCHEDULED);
        assertThat(transactions.findById(busy.id()).orElseThrow().lastError().code())
                .isEqualTo("ALREADY_PROCESSING_REQUEST");
        makeRetryDue(busy.id());
        worker.recoverDue();

        verify(toss, never()).confirm(any(), any());
        assertThat(keyOf(busy)).isEqualTo(busy.idempotencyKey().value());
        assertThat(statusOf(busy)).isEqualTo(TransactionStatus.SUCCEEDED);
    }

    // 리뷰 Medium: 키를 바꾼 뒤 멈추면(전송 중이었을 수 있다) 다음 작업자는 조회 → 또 교체가 아니라 같은(새) 키로 이어 간다
    @Test
    void crashAfterRotationResumesWithTheNewKey() {
        ClaimedTransaction started = start();
        transactionTemplate.executeWithoutResult(s -> ledger.resolve(started, new Outcome.Unknown(UNLISTED)));
        ClaimedTransaction rotated = transactionTemplate.execute(s -> ledger.rotateIdempotencyKey(started));
        expireLease(started.transaction().id());
        given(toss.confirm(any(), any())).willReturn(done(started.transaction(), AMOUNT));

        worker.recoverDue();

        verify(toss).confirm(any(), eq(new TossIdempotencyKey(rotated.transaction().idempotencyKey().value())));
        verify(toss, never()).findByPaymentKey(anyString());
        assertThat(statusOf(started.transaction())).isEqualTo(TransactionStatus.SUCCEEDED);
    }

    // 리뷰 Medium: 새 키의 거절은 그 사이 앞선 요청이 승인됐을 수 있어 조회로 확인한 뒤에만 실패로 확정한다
    @Test
    void rejectionOfNewKeyIsCheckedAgainstLookup() {
        PaymentTransaction retry = startedThenRetryDue(PROVIDER_ERROR);
        given(toss.findByPaymentKey(paymentKey.value()))
                .willReturn(found(retry, TossPaymentStatus.IN_PROGRESS, AMOUNT))
                .willReturn(found(retry, TossPaymentStatus.DONE, AMOUNT));
        given(toss.confirm(any(), any()))
                .willReturn(new TossCommandResult.Rejected("NOT_FOUND_PAYMENT_SESSION", "결제 시간이 만료되었습니다"));

        worker.recoverDue();

        verify(toss, times(2)).findByPaymentKey(paymentKey.value());
        assertThat(statusOf(retry)).isEqualTo(TransactionStatus.SUCCEEDED);
        assertThat(settledPayload().get("result").asString()).isEqualTo("APPROVED");
    }

    // 리스가 끝난 뒤 늦게 온 워커의 결과는 반영되지 않는다(0행) — 결과는 리스를 쥔 쪽이 확정한다
    @Test
    void lateResultAfterLeaseExpiryIsNotApplied() {
        PaymentTransaction lost = startedThenLeaseExpired(null);
        given(toss.confirm(any(), any())).willAnswer(invocation -> {
            expireLease(lost.id());
            return done(lost, AMOUNT);
        });

        worker.recoverDue();

        PaymentTransaction after = transactions.findById(lost.id()).orElseThrow();
        assertThat(after.status()).isEqualTo(TransactionStatus.PROCESSING);
        assertThat(paymentsOfTarget()).isZero();
        assertThat(settledEvents()).isZero();
    }

    // 인스턴스 둘이 동시에 돌아도 거래마다 토스 승인은 한 번이다(SKIP LOCKED + 리스)
    @Test
    void concurrentWorkersSendEachTransactionOnce() throws Exception {
        List<PaymentTransaction> lost = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            target = PaymentFixtures.newOrderTarget();
            paymentKey = PaymentFixtures.newProviderPayment();
            lost.add(startedThenLeaseExpired(null));
        }
        Map<String, AtomicInteger> sent = new ConcurrentHashMap<>();
        given(toss.confirm(any(), any())).willAnswer(invocation -> {
            TossConfirmRequest request = invocation.getArgument(0);
            sent.computeIfAbsent(request.orderId(), k -> new AtomicInteger()).incrementAndGet();
            return new TossCommandResult.Succeeded(new TossPayment(request.paymentKey(), request.orderId(),
                    TossPaymentStatus.DONE, request.amount(), request.amount(), "카드", APPROVED_AT, "tx"));
        });

        List<Concurrently.Outcome<Integer>> outcomes = Concurrently.run(2, i -> () -> worker.recoverDue());

        assertThat(outcomes).allSatisfy(o -> assertThat(o.error()).isNull());
        assertThat(outcomes.stream().mapToInt(Concurrently.Outcome::value).sum()).isEqualTo(lost.size());
        for (PaymentTransaction transaction : lost) {
            assertThat(sent.get(transaction.providerOrderId().value())).hasValue(1);
            assertThat(statusOf(transaction)).isEqualTo(TransactionStatus.SUCCEEDED);
        }
    }

    // ---- 준비 ----

    /** 시작해 리스를 쥔 뒤 결과 불명(error) 또는 반영 전 멈춤(null)으로 남기고 리스를 끝낸다. */
    private PaymentTransaction startedThenLeaseExpired(ProviderError error) {
        ClaimedTransaction started = start();
        if (error != null) {
            transactionTemplate.executeWithoutResult(s -> ledger.resolve(started, new Outcome.Unknown(error)));
        }
        expireLease(started.transaction().id());
        return started.transaction();
    }

    private PaymentTransaction startedThenRetryDue(ProviderError error) {
        ClaimedTransaction started = start();
        transactionTemplate.executeWithoutResult(s ->
                ledger.resolve(started, new Outcome.InProgress(error, Duration.ofSeconds(10))));
        makeRetryDue(started.transaction().id());
        return started.transaction();
    }

    private ClaimedTransaction start() {
        PaymentTransaction pending = transactionTemplate.execute(s -> ledger.openCapture(target, AMOUNT));
        return transactionTemplate.execute(s -> ledger.start(pending, target, paymentKey, AMOUNT)).orElseThrow();
    }

    private TossCommandResult done(PaymentTransaction transaction, Money amount) {
        return new TossCommandResult.Succeeded(new TossPayment(paymentKey.value(), transaction.providerOrderId().value(),
                TossPaymentStatus.DONE, amount.amount().longValueExact(), amount.amount().longValueExact(), "카드",
                APPROVED_AT, "tx"));
    }

    private TossLookupResult found(PaymentTransaction transaction, TossPaymentStatus status, Money amount) {
        Instant approvedAt = status == TossPaymentStatus.DONE ? APPROVED_AT : null;
        return new TossLookupResult.Found(new TossPayment(paymentKey.value(), transaction.providerOrderId().value(),
                status, amount.amount().longValueExact(), amount.amount().longValueExact(), "카드", approvedAt, "tx"));
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

    private int paymentsOfTarget() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM payments WHERE target_type = 'ORDER' AND target_id = ?",
                Integer.class, target.id());
    }

    private int settledEvents() {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM payment_outbox_events
                 WHERE aggregate_type = 'ORDER' AND aggregate_id = ? AND event_type = 'ORDER_PAYMENT_SETTLED'
                """, Integer.class, target.id());
    }

    private JsonNode settledPayload() {
        List<String> payloads = jdbcTemplate.queryForList("""
                SELECT payload FROM payment_outbox_events
                 WHERE aggregate_type = 'ORDER' AND aggregate_id = ? AND event_type = 'ORDER_PAYMENT_SETTLED'
                """, String.class, target.id());
        assertThat(payloads).hasSize(1);
        assertThat(payloads.getFirst()).doesNotContain(paymentKey.value());
        return jsonMapper.readTree(payloads.getFirst());
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

    /** 다른 테스트가 남긴 복구 후보를 에스컬레이션해 이 테스트의 후보만 남긴다. */
    private void parkOtherRecoverables() {
        jdbcTemplate.update("""
                UPDATE payment_transactions SET escalated_at = UTC_TIMESTAMP(6)
                 WHERE escalated_at IS NULL AND status IN ('PROCESSING', 'RETRY_SCHEDULED')
                """);
    }
}
