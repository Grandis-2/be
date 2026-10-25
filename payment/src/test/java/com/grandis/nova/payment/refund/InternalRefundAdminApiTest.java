package com.grandis.nova.payment.refund;

import com.grandis.nova.common.UuidBinary;
import com.grandis.nova.common.security.BearerTokens;
import com.grandis.nova.common.security.JwtTokenProvider;
import com.grandis.nova.common.security.RevocationChecker;
import com.grandis.nova.common.security.Role;
import com.grandis.nova.common.security.TokenType;
import com.grandis.nova.common.testing.Concurrently;
import com.grandis.nova.payment.ClaimedTransaction;
import com.grandis.nova.payment.PaymentLedger;
import com.grandis.nova.payment.client.toss.TossLookupResult;
import com.grandis.nova.payment.client.toss.TossPayment;
import com.grandis.nova.payment.client.toss.TossPaymentClient;
import com.grandis.nova.payment.client.toss.TossPaymentStatus;
import com.grandis.nova.payment.client.toss.UnknownReason;
import com.grandis.nova.payment.domain.enums.PaymentStatus;
import com.grandis.nova.payment.domain.enums.TransactionStatus;
import com.grandis.nova.payment.domain.enums.TransactionType;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 멈춘 환불의 관리자 조치(/internal/refund-issues · /internal/refunds/**). 진짜 관리자 토큰으로 보안 체인까지 태우고, 토스는 조회만 대역이다.
 * 환불 상태(확정 실패 · 에스컬레이션)는 원장으로 만든다. DB 를 공유하므로 목록 단정은 이 테스트가 만든 주문으로만 한다.
 */
@PaymentIntegrationTest
@AutoConfigureMockMvc
class InternalRefundAdminApiTest {

    static final Money AMOUNT = Money.won(15000);
    static final long WON = 15000;
    static final Instant APPROVED_AT = Instant.parse("2026-10-04T01:00:00Z");
    static final Instant CANCELED_AT = Instant.parse("2026-10-24T05:00:00Z");

    @Autowired
    MockMvc mockMvc;

    @MockitoSpyBean
    PaymentLedger ledger;

    @Autowired
    PaymentTransactionReader transactions;

    @Autowired
    PaymentReader payments;

    @Autowired
    RefundSettlement settlement;

    @Autowired
    RecoveryWorker worker;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    JwtTokenProvider tokens;

    @MockitoBean
    RevocationChecker revocationChecker;

    @MockitoBean
    TossPaymentClient toss;

    String admin;
    PaymentTarget target;
    ProviderPaymentKey paymentKey;

    @BeforeEach
    void setUp() {
        admin = tokens.create("admin-kim", Role.ADMIN, UUID.randomUUID(), TokenType.ACCESS);
        target = PaymentFixtures.newOrderTarget();
        paymentKey = PaymentFixtures.newProviderPayment();
        RecoveryCandidates.parkOthers(jdbcTemplate);
    }

    // ---- 목록 ----

    @Test
    void 멈춘_환불을_사유와_할_수_있는_조치로_보여_준다() throws Exception {
        PaymentTransaction rejected = rejectedRefund();
        PaymentTarget escalatedTarget = target;
        target = PaymentFixtures.newOrderTarget();
        paymentKey = PaymentFixtures.newProviderPayment();
        PaymentTransaction mismatch = escalatedRefund(RefundRecovery.REFUND_MISMATCH);

        asAdmin(get("/internal/refund-issues")).andExpect(status().isOk());
        asAdmin(get("/internal/refund-issues").param("orderIds", escalatedTarget.id().toString(), target.id().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].orderId").value(escalatedTarget.id().toString()))
                .andExpect(jsonPath("$.data[0].reason").value("REFUND_REJECTED"))
                .andExpect(jsonPath("$.data[0].paymentStatus").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data[0].refund.transactionId").value(rejected.id().toString()))
                .andExpect(jsonPath("$.data[0].refund.status").value("FAILED"))
                .andExpect(jsonPath("$.data[0].actions").value(contains("RETRY", "MANUAL_COMPLETE")))
                .andExpect(jsonPath("$.data[1].reason").value("ESCALATED_MISMATCH"))
                .andExpect(jsonPath("$.data[1].refund.transactionId").value(mismatch.id().toString()))
                .andExpect(jsonPath("$.data[1].actions").value(contains("MANUAL_COMPLETE")));
        assertThat(stuckIds()).contains(escalatedTarget.id().toString(), target.id().toString());
        asAdmin(get("/internal/refund-issues").param("staleHours", "0")).andExpect(status().isBadRequest());
    }

    @Test
    void 결제가_없거나_환불_거래가_없거나_이미_환불이면_그대로_알린다() throws Exception {
        UUID noPayment = UUID.randomUUID();
        paid();
        asAdmin(get("/internal/refund-issues").param("orderIds", noPayment.toString(), target.id().toString()))
                .andExpect(jsonPath("$.data[0].reason").value("NO_PAYMENT"))
                .andExpect(jsonPath("$.data[1].reason").value("NO_REFUND_TRANSACTION"))
                .andExpect(jsonPath("$.data[1].refund").doesNotExist());
    }

    // ---- 다시 보내기 ----

    @Test
    void 확정_실패한_환불은_결제사가_아직_취소하지_않았으면_새_거래로_다시_연다() throws Exception {
        PaymentTransaction rejected = rejectedRefund();
        given(toss.findByPaymentKey(paymentKey.value())).willReturn(found(TossPaymentStatus.DONE, WON, WON, List.of()));

        asAdmin(retry(rejected.id(), "카드사 한도 해소 확인"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("PENDING"));

        PaymentTransaction last = lastRefund();
        assertThat(last.id()).isNotEqualTo(rejected.id());
        assertThat(last.status()).isEqualTo(TransactionStatus.PENDING);
        assertThat(last.idempotencyKey()).as("새 멱등 키 — 같은 키면 결제사가 저장한 거절만 돌려준다")
                .isNotEqualTo(rejected.idempotencyKey());
        assertThat(statusOf(rejected)).isEqualTo(TransactionStatus.FAILED);
        assertThat(action()).containsEntry("action", "RETRY").containsEntry("requested_by", "admin-kim")
                .containsEntry("note", "카드사 한도 해소 확인").containsEntry("provider_status", "DONE");
        assertThat(settledEvents()).as("다시 열기는 주문에 알리지 않는다 — 결과가 날 때 알린다").isZero();
    }

    @Test
    void 결과를_끝내_몰라_멈춘_환불은_닫고_새_거래로_다시_연다_주문에는_실패를_알리지_않는다() throws Exception {
        PaymentTransaction escalated = escalatedRefund(RefundRecovery.UNRESOLVED);
        given(toss.findByPaymentKey(paymentKey.value())).willReturn(found(TossPaymentStatus.DONE, WON, WON, List.of()));

        asAdmin(retry(escalated.id(), "토스 장애 끝남")).andExpect(status().isAccepted());

        PaymentTransaction closed = transactions.findById(escalated.id()).orElseThrow();
        assertThat(closed.status()).isEqualTo(TransactionStatus.FAILED);
        assertThat(closed.lastError().code()).isEqualTo(RefundAdmin.ADMIN_RETRY);
        assertThat(lastRefund().status()).isEqualTo(TransactionStatus.PENDING);
        assertThat(action()).containsEntry("action", "RETRY_AFTER_ESCALATION");
        assertThat(settledEvents()).isZero();
    }

    @Test
    void 다시_보내도_같은_멈춤은_받지_않는다() throws Exception {
        PaymentTransaction notFound = escalatedRefund("LOOKUP_NOT_FOUND");

        asAdmin(retry(notFound.id(), "다시"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("REFUND_NOT_RETRYABLE"))
                .andExpect(jsonPath("$.error.details.reason").value("ESCALATED_NOT_FOUND"));
        verify(toss, never()).findByPaymentKey(any());
        assertThat(lastRefund().id()).isEqualTo(notFound.id());
    }

    @Test
    void 결제사가_이미_취소했거나_일부라도_취소했으면_다시_보내지_않는다() throws Exception {
        PaymentTransaction rejected = rejectedRefund();
        given(toss.findByPaymentKey(paymentKey.value()))
                .willReturn(found(TossPaymentStatus.CANCELED, WON, 0, List.of(new TossPayment.Cancel(CANCELED_AT, WON))))
                .willReturn(found(TossPaymentStatus.PARTIAL_CANCELED, WON, 5000,
                        List.of(new TossPayment.Cancel(CANCELED_AT, 10000))));

        asAdmin(retry(rejected.id(), "다시"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("REFUND_NOT_RETRYABLE"))
                .andExpect(jsonPath("$.error.details.reason").value("PROVIDER_CANCELED"))
                .andExpect(jsonPath("$.error.details.providerStatus").value("CANCELED"));
        asAdmin(retry(rejected.id(), "다시"))
                .andExpect(jsonPath("$.error.details.reason").value("PROVIDER_NOT_UNTOUCHED"))
                .andExpect(jsonPath("$.error.details.balanceAmount").value(5000));
        assertThat(lastRefund().id()).isEqualTo(rejected.id());
        assertThat(actionCount()).isZero();
    }

    @Test
    void 결제사_조회를_모르면_아무것도_바꾸지_않는다() throws Exception {
        PaymentTransaction rejected = rejectedRefund();
        given(toss.findByPaymentKey(paymentKey.value()))
                .willReturn(new TossLookupResult.Unknown(UnknownReason.TIMEOUT, null));

        asAdmin(retry(rejected.id(), "다시"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("REFUND_PROVIDER_UNAVAILABLE"));
        assertThat(lastRefund().id()).isEqualTo(rejected.id());
        assertThat(actionCount()).isZero();
    }

    @Test
    void 본_거래가_마지막이_아니거나_진행_중이거나_이미_환불이면_거절한다() throws Exception {
        PaymentTransaction rejected = rejectedRefund();
        given(toss.findByPaymentKey(paymentKey.value())).willReturn(found(TossPaymentStatus.DONE, WON, WON, List.of()));
        asAdmin(retry(UUID.randomUUID(), "다시"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("REFUND_STATE_CHANGED"))
                .andExpect(jsonPath("$.error.details.currentTransactionId").value(rejected.id().toString()));

        asAdmin(retry(rejected.id(), "다시")).andExpect(status().isAccepted());
        PaymentTransaction reopened = lastRefund();
        asAdmin(retry(reopened.id(), "또"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("REFUND_IN_PROGRESS"));
        asAdmin(manualComplete(reopened.id(), "또"))
                .andExpect(jsonPath("$.error.code").value("REFUND_IN_PROGRESS"));

        complete(reopened);
        asAdmin(retry(reopened.id(), "또"))
                .andExpect(jsonPath("$.error.code").value("REFUND_ALREADY_COMPLETED"));
        asAdmin(retry(reopened.id(), " ")).andExpect(status().isBadRequest());
    }

    @Test
    void 두_관리자가_같은_멈춘_환불을_함께_다시_보내도_한쪽만_된다() throws Exception {
        PaymentTransaction escalated = escalatedRefund(RefundRecovery.UNRESOLVED);
        given(toss.findByPaymentKey(paymentKey.value())).willReturn(found(TossPaymentStatus.DONE, WON, WON, List.of()));

        List<Concurrently.Outcome<Integer>> outcomes = Concurrently.run(4, i -> () -> asAdmin(retry(escalated.id(), "동시"))
                .andReturn().getResponse().getStatus());

        assertThat(outcomes).allMatch(Concurrently.Outcome::succeeded);
        assertThat(outcomes.stream().map(Concurrently.Outcome::value).filter(code -> code == 202)).hasSize(1);
        assertThat(refunds()).as("닫은 거래 하나 + 새 거래 하나").hasSize(2);
        assertThat(actionCount()).isEqualTo(1);
    }

    @Test
    void 다시_연_환불은_워커가_집어_보낸다() throws Exception {
        PaymentTransaction rejected = rejectedRefund();
        given(toss.findByPaymentKey(paymentKey.value())).willReturn(found(TossPaymentStatus.DONE, WON, WON, List.of()));
        asAdmin(retry(rejected.id(), "다시")).andExpect(status().isAccepted());
        given(toss.cancel(any(), any())).willReturn(new com.grandis.nova.payment.client.toss.TossCommandResult.Succeeded(
                new TossPayment(paymentKey.value(), "order", TossPaymentStatus.CANCELED, WON, 0, "카드", APPROVED_AT, "tx",
                        List.of(new TossPayment.Cancel(CANCELED_AT, WON)))));

        worker.recoverDue();

        assertThat(lastRefund().status()).isEqualTo(TransactionStatus.SUCCEEDED);
        assertThat(payments.findPaymentByTarget(target).orElseThrow().status()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(settledEvents()).isEqualTo(1);
    }

    @Test
    void 리스가_아직_살아_있는_에스컬레이션은_넘겨받지_않는다() throws Exception {
        ClaimedTransaction sent = sentRefund();
        transactionTemplate.executeWithoutResult(s -> ledger.escalate(sent, new ProviderError(RefundRecovery.UNRESOLVED, "멈춤")));
        given(toss.findByPaymentKey(paymentKey.value())).willReturn(found(TossPaymentStatus.DONE, WON, WON, List.of()));

        asAdmin(retry(sent.transaction().id(), "다시"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("REFUND_STATE_CHANGED"));
        PaymentTransaction after = transactions.findById(sent.transaction().id()).orElseThrow();
        assertThat(after.status()).isEqualTo(TransactionStatus.PROCESSING);
        assertThat(after.leaseToken()).as("리스를 넘기지 않았다").isEqualTo(sent.transaction().leaseToken());
        assertThat(refunds()).hasSize(1);
        assertThat(actionCount()).isZero();
    }

    @Test
    void 넘겨받은_뒤_다시_열기가_실패하면_넘겨받기와_닫기까지_되돌아간다() throws Exception {
        PaymentTransaction escalated = escalatedRefund(RefundRecovery.UNRESOLVED);
        given(toss.findByPaymentKey(paymentKey.value())).willReturn(found(TossPaymentStatus.DONE, WON, WON, List.of()));
        // 원장은 트랜잭션 프록시 안의 대역이다 — 프록시를 거치면 MANDATORY 에 걸리므로 대역 자체에 건다
        PaymentLedger spy = AopTestUtils.getUltimateTargetObject(ledger);
        doThrow(new IllegalStateException("다시 열기 실패")).when(spy).reopenRefund(any(), any());

        asAdmin(retry(escalated.id(), "다시")).andExpect(status().isInternalServerError());

        PaymentTransaction after = transactions.findById(escalated.id()).orElseThrow();
        assertThat(after.status()).as("닫기가 되돌아갔다").isEqualTo(TransactionStatus.PROCESSING);
        assertThat(after.isEscalated()).isTrue();
        assertThat(after.leaseToken()).as("넘겨받기도 되돌아갔다").isEqualTo(escalated.leaseToken());
        assertThat(refunds()).hasSize(1);
        assertThat(actionCount()).isZero();
    }

    @Test
    void 같은_확정_실패에_다시_보내기와_직접_환불_기록이_겹치면_한쪽만_된다() throws Exception {
        PaymentTransaction rejected = rejectedRefund();
        ThreadLocal<TossLookupResult> lookup = new ThreadLocal<>();
        given(toss.findByPaymentKey(paymentKey.value())).willAnswer(invocation -> lookup.get());
        TossLookupResult untouched = found(TossPaymentStatus.DONE, WON, WON, List.of());
        TossLookupResult canceled = found(TossPaymentStatus.CANCELED, WON, 0, List.of(new TossPayment.Cancel(CANCELED_AT, WON)));

        List<Concurrently.Outcome<Integer>> outcomes = Concurrently.run(2, i -> () -> {
            lookup.set(i == 0 ? untouched : canceled);
            return asAdmin(i == 0 ? retry(rejected.id(), "다시") : manualComplete(rejected.id(), "직접"))
                    .andReturn().getResponse().getStatus();
        });

        assertThat(outcomes).allMatch(Concurrently.Outcome::succeeded);
        assertThat(outcomes.stream().map(Concurrently.Outcome::value).filter(code -> code / 100 == 2)).hasSize(1);
        assertThat(outcomes.stream().map(Concurrently.Outcome::value).filter(code -> code == 409)).hasSize(1);
        assertThat(refunds()).as("확정 실패 + 새 거래 하나").hasSize(2);
        assertThat(actionCount()).isEqualTo(1);
    }

    @Test
    void 직접_환불_기록_두_개가_겹치면_한_번만_기록하고_한_번만_알린다() throws Exception {
        PaymentTransaction rejected = rejectedRefund();
        given(toss.findByPaymentKey(paymentKey.value()))
                .willReturn(found(TossPaymentStatus.CANCELED, WON, 0, List.of(new TossPayment.Cancel(CANCELED_AT, WON))));

        List<Concurrently.Outcome<Integer>> outcomes = Concurrently.run(3, i -> () ->
                asAdmin(manualComplete(rejected.id(), "직접")).andReturn().getResponse().getStatus());

        assertThat(outcomes).allMatch(Concurrently.Outcome::succeeded);
        assertThat(outcomes.stream().map(Concurrently.Outcome::value).filter(code -> code == 200)).hasSize(1);
        assertThat(settledEvents()).isEqualTo(1);
        assertThat(actionCount()).isEqualTo(1);
    }

    @Test
    void 동시_조치_상한을_넘는_요청은_기다리지_않고_503_이며_아무것도_바꾸지_않는다() throws Exception {
        PaymentTransaction rejected = rejectedRefund();
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(2);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        given(toss.findByPaymentKey(paymentKey.value())).willAnswer(invocation -> {
            entered.countDown();
            release.await(30, java.util.concurrent.TimeUnit.SECONDS);
            return found(TossPaymentStatus.CANCELED, WON, 0, List.of(new TossPayment.Cancel(CANCELED_AT, WON)));
        });
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            List<java.util.concurrent.Future<Integer>> holding = List.of(
                    executor.submit(() -> asAdmin(manualComplete(rejected.id(), "직접")).andReturn().getResponse().getStatus()),
                    executor.submit(() -> asAdmin(manualComplete(rejected.id(), "직접")).andReturn().getResponse().getStatus()));
            assertThat(entered.await(30, java.util.concurrent.TimeUnit.SECONDS)).as("두 조치가 결제사 조회 중").isTrue();

            asAdmin(manualComplete(rejected.id(), "세 번째"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.error.code").value("REFUND_PROVIDER_UNAVAILABLE"))
                    .andExpect(jsonPath("$.error.details.reason").value("TOO_MANY_CONCURRENT"));
            verify(toss, times(2)).findByPaymentKey(any());
            assertThat(statusOf(rejected)).isEqualTo(TransactionStatus.FAILED);
            assertThat(actionCount()).isZero();

            release.countDown();
            assertThat(List.of(holding.get(0).get(), holding.get(1).get())).containsExactlyInAnyOrder(200, 409);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
        assertThat(actionCount()).isEqualTo(1);
        // 자리를 돌려줬다 — 끝난 조치(완료 · 409) · 상한 거절(503) 뒤에도 새 조치가 상한에 걸리지 않는다. 한 경로라도 자리를 새면 둘째가 503 이다
        for (int i = 0; i < 2; i++) {
            asAdmin(manualComplete(rejected.id(), "다시")).andExpect(status().isConflict());
        }
    }

    // ---- 직접 환불 기록 ----

    @Test
    void 결제사에서_전액_취소가_확인되면_확정_실패한_환불을_환불_완료로_기록하고_주문에_알린다() throws Exception {
        PaymentTransaction rejected = rejectedRefund();
        given(toss.findByPaymentKey(paymentKey.value()))
                .willReturn(found(TossPaymentStatus.CANCELED, WON, 0, List.of(new TossPayment.Cancel(CANCELED_AT, WON))));

        asAdmin(manualComplete(rejected.id(), "상점 관리자에서 직접 취소"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.refundedAt").value("2026-10-24T05:00:00Z"));

        assertThat(lastRefund().status()).isEqualTo(TransactionStatus.SUCCEEDED);
        assertThat(payments.findPaymentByTarget(target).orElseThrow().status()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(settledEvents()).isEqualTo(1);
        assertThat(action()).containsEntry("action", "MANUAL_COMPLETE").containsEntry("provider_status", "CANCELED");
        verify(toss, never()).cancel(any(), any());
    }

    @Test
    void 에스컬레이션된_환불도_전액_취소가_확인되면_그_거래를_환불_완료로_기록한다() throws Exception {
        PaymentTransaction mismatch = escalatedRefund(RefundRecovery.REFUND_MISMATCH);
        given(toss.findByPaymentKey(paymentKey.value()))
                .willReturn(found(TossPaymentStatus.CANCELED, WON, 0, List.of(new TossPayment.Cancel(CANCELED_AT, WON))));

        asAdmin(manualComplete(mismatch.id(), "확인함")).andExpect(status().isOk());

        assertThat(statusOf(mismatch)).isEqualTo(TransactionStatus.SUCCEEDED);
        assertThat(refunds()).hasSize(1);
        assertThat(settledEvents()).isEqualTo(1);
    }

    @Test
    void 결제사에서_전액_취소가_아니면_기록하지_않는다() throws Exception {
        PaymentTransaction rejected = rejectedRefund();
        given(toss.findByPaymentKey(paymentKey.value()))
                .willReturn(found(TossPaymentStatus.DONE, WON, WON, List.of()))
                .willReturn(found(TossPaymentStatus.CANCELED, WON, 0, List.of(new TossPayment.Cancel(null, WON))))
                .willReturn(new TossLookupResult.NotFound("NOT_FOUND_PAYMENT"));

        asAdmin(manualComplete(rejected.id(), "취소함"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("REFUND_NOT_CANCELED_AT_PROVIDER"))
                .andExpect(jsonPath("$.error.details.providerStatus").value("DONE"))
                .andExpect(jsonPath("$.error.details.balanceAmount").value(WON));
        asAdmin(manualComplete(rejected.id(), "취소함"))
                .andExpect(jsonPath("$.error.code").value("REFUND_NOT_CANCELED_AT_PROVIDER"));
        asAdmin(manualComplete(rejected.id(), "취소함"))
                .andExpect(jsonPath("$.error.details.reason").value("PROVIDER_NOT_FOUND"));
        assertThat(statusOf(rejected)).isEqualTo(TransactionStatus.FAILED);
        assertThat(payments.findPaymentByTarget(target).orElseThrow().status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(settledEvents()).isZero();
        assertThat(actionCount()).isZero();
    }

    // ---- 준비 ----

    private void paid() {
        PaymentTransaction pending = transactionTemplate.execute(s -> ledger.openCapture(target, AMOUNT));
        ClaimedTransaction started = transactionTemplate.execute(s -> ledger.start(pending, target, paymentKey, AMOUNT))
                .orElseThrow();
        transactionTemplate.executeWithoutResult(s -> ledger.resolve(started, new Outcome.Confirmed(APPROVED_AT)));
    }

    private ClaimedTransaction sentRefund() {
        paid();
        PaymentTransaction opened = transactionTemplate.execute(s -> ledger.openRefund(target));
        return transactionTemplate.execute(s -> ledger.claim(opened)).orElseThrow();
    }

    /** 결제사가 환불을 거절해 실패로 확정했다. 결과 이벤트는 실제 경로처럼 남지만 단정은 그 뒤의 변화만 본다. */
    private PaymentTransaction rejectedRefund() {
        ClaimedTransaction sent = sentRefund();
        transactionTemplate.executeWithoutResult(s -> ledger.resolve(sent,
                new Outcome.Rejected(new ProviderError("NOT_CANCELABLE_PAYMENT", "취소할 수 없는 결제"))));
        clearSettledEvents();
        return sent.transaction();
    }

    /** 복구가 이 사유로 멈췄다. 리스는 남아 있고 이미 끝났다. */
    private PaymentTransaction escalatedRefund(String code) {
        ClaimedTransaction sent = sentRefund();
        transactionTemplate.executeWithoutResult(s -> ledger.escalate(sent, new ProviderError(code, "멈춤")));
        jdbcTemplate.update("UPDATE payment_transactions SET lease_expires_at = UTC_TIMESTAMP(6) - INTERVAL 1 SECOND "
                + "WHERE id = ?", UuidBinary.toBytes(sent.transaction().id()));
        return sent.transaction();
    }

    private void complete(PaymentTransaction pending) {
        ClaimedTransaction claimed = transactionTemplate.execute(s -> ledger.claim(pending)).orElseThrow();
        settlement.settle(claimed, new Outcome.Confirmed(CANCELED_AT));
    }

    private TossLookupResult found(TossPaymentStatus status, long total, long balance, List<TossPayment.Cancel> cancels) {
        return new TossLookupResult.Found(new TossPayment(paymentKey.value(), "order", status, total, balance, "카드",
                APPROVED_AT, "tx", cancels));
    }

    // ---- 확인 ----

    private PaymentTransaction lastRefund() {
        return refunds().getLast();
    }

    private List<PaymentTransaction> refunds() {
        return transactions.findTransactionsByTarget(target).stream()
                .filter(transaction -> transaction.type() == TransactionType.REFUND).toList();
    }

    private TransactionStatus statusOf(PaymentTransaction transaction) {
        return transactions.findById(transaction.id()).orElseThrow().status();
    }

    private Map<String, Object> action() {
        return jdbcTemplate.queryForMap("SELECT action, requested_by, note, provider_status FROM payment_refund_admin_actions "
                + "WHERE target_type = 'ORDER' AND target_id = ?", (Object) UuidBinary.toBytes(target.id()));
    }

    private int actionCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM payment_refund_admin_actions WHERE target_id = ?",
                Integer.class, (Object) UuidBinary.toBytes(target.id()));
    }

    private int settledEvents() {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM payment_outbox_events
                 WHERE aggregate_type = 'ORDER' AND aggregate_id = ? AND event_type = 'ORDER_REFUND_SETTLED'
                """, Integer.class, UuidBinary.toBytes(target.id()));
    }

    private void clearSettledEvents() {
        jdbcTemplate.update("DELETE FROM payment_outbox_events WHERE aggregate_type = 'ORDER' AND aggregate_id = ?",
                (Object) UuidBinary.toBytes(target.id()));
    }

    private List<String> stuckIds() throws Exception {
        String body = asAdmin(get("/internal/refund-issues")).andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.read(body, "$.data[*].orderId");
    }

    // ---- 요청 ----

    private MockHttpServletRequestBuilder retry(UUID expected, String note) {
        return post("/internal/refunds/{orderId}/retry", target.id()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedTransactionId\":\"%s\",\"note\":\"%s\"}".formatted(expected, note));
    }

    private MockHttpServletRequestBuilder manualComplete(UUID expected, String note) {
        return post("/internal/refunds/{orderId}/manual-complete", target.id()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedTransactionId\":\"%s\",\"note\":\"%s\"}".formatted(expected, note));
    }

    private ResultActions asAdmin(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header(BearerTokens.HEADER, BearerTokens.value(admin)));
    }
}
