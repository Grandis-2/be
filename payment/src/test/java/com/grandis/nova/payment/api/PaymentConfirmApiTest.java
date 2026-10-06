package com.grandis.nova.payment.api;

import com.grandis.nova.common.security.BearerTokens;
import com.grandis.nova.common.security.JwtTokenProvider;
import com.grandis.nova.common.security.RevocationChecker;
import com.grandis.nova.common.security.Role;
import com.grandis.nova.common.security.TokenType;
import com.grandis.nova.payment.client.toss.TossCommandResult;
import com.grandis.nova.payment.client.toss.TossConfirmRequest;
import com.grandis.nova.payment.client.toss.TossIdempotencyKey;
import com.grandis.nova.payment.client.toss.TossPayment;
import com.grandis.nova.payment.client.toss.TossPaymentClient;
import com.grandis.nova.payment.client.toss.TossPaymentStatus;
import com.grandis.nova.payment.client.toss.UnknownReason;
import com.grandis.nova.payment.confirm.PendingCaptureExpiry;
import com.grandis.nova.payment.domain.model.PaymentTransaction;
import com.grandis.nova.payment.prepare.PreparePaymentService;
import com.grandis.nova.payment.support.PaymentFixtures;
import com.grandis.nova.payment.support.PaymentIntegrationTest;
import com.grandis.nova.payment.vo.Money;
import com.grandis.nova.payment.vo.PaymentTarget;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /internal/payment-attempts/{providerOrderId}/confirm — 거래 시작 · 토스 승인 · 결과 반영 · 결과 이벤트. MySQL 위에서 돌고
 * 토스 클라이언트만 대역이다(HTTP → 분류는 TossPaymentClientTest 가 본다).
 *
 * 약속: 4xx 는 거래를 시작하지 않았다(토스도 부르지 않았다). 시작했으면 200 이고 결과는 APPROVED · DECLINED · PENDING.
 */
@PaymentIntegrationTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class PaymentConfirmApiTest {

    static final long AMOUNT = 15000;
    static final Instant APPROVED_AT = Instant.parse("2026-10-02T03:04:05Z");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    JsonMapper jsonMapper;

    @Autowired
    JwtTokenProvider tokens;

    @Autowired
    PreparePaymentService prepareService;

    @Autowired
    PendingCaptureExpiry expiry;

    @MockitoBean
    RevocationChecker revocationChecker;

    @MockitoBean
    TossPaymentClient toss;

    String user;
    PaymentTarget target;
    PaymentTransaction opened;
    String paymentKey;

    @BeforeEach
    void setUp() {
        user = tokens.create("101", Role.USER, UUID.randomUUID(), TokenType.ACCESS);
        target = PaymentFixtures.newOrderTarget();
        opened = prepareService.open(target, Money.won(AMOUNT));
        paymentKey = PaymentFixtures.newProviderPayment().value();
    }

    @Test
    void approvedConfirmRecordsPaymentAndSettledEvent(CapturedOutput output) throws Exception {
        tossAnswers(done(AMOUNT));

        confirm(opened, target, AMOUNT)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("APPROVED"))
                .andExpect(jsonPath("$.data.declineReason").doesNotExist());

        assertThat(transaction()).containsEntry("status", "SUCCEEDED").containsEntry("attempt_count", 1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT status FROM payments WHERE target_type = 'ORDER' AND target_id = ?
                """, String.class, target.id())).isEqualTo("SUCCEEDED");
        JsonNode payload = settledPayload();
        assertThat(payload.propertyNames()).containsExactlyInAnyOrder(
                "providerOrderId", "result", "amount", "approvedAt", "declineReason");
        assertThat(payload.get("providerOrderId").asString()).isEqualTo(opened.providerOrderId().value());
        assertThat(payload.get("result").asString()).isEqualTo("APPROVED");
        assertThat(payload.get("amount").asLong()).isEqualTo(AMOUNT);
        assertThat(Instant.parse(payload.get("approvedAt").asString())).isEqualTo(APPROVED_AT);
        assertThat(payload.get("declineReason").isNull()).isTrue();
        assertThat(output.getAll()).doesNotContain(paymentKey);
    }

    // 토스에는 거래 행의 멱등 키 · 저장 금액 · 결제창 번호를 보낸다
    @Test
    void sendsStoredIdempotencyKeyAndAmount() throws Exception {
        tossAnswers(done(AMOUNT));

        confirm(opened, target, AMOUNT).andExpect(status().isOk());

        verify(toss).confirm(eq(new TossConfirmRequest(paymentKey, opened.providerOrderId().value(), AMOUNT)),
                eq(new TossIdempotencyKey(opened.idempotencyKey().value())));
    }

    @Test
    void declinedConfirmFailsTransactionAndSendsReason() throws Exception {
        tossAnswers(new TossCommandResult.Rejected("REJECT_CARD_PAYMENT", "한도초과"));

        confirm(opened, target, AMOUNT)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("DECLINED"))
                .andExpect(jsonPath("$.data.declineReason").value("CARD_REJECTED"));

        assertThat(transaction()).containsEntry("status", "FAILED").containsEntry("last_error_code", "REJECT_CARD_PAYMENT");
        JsonNode payload = settledPayload();
        assertThat(payload.get("result").asString()).isEqualTo("DECLINED");
        assertThat(payload.get("declineReason").asString()).isEqualTo("CARD_REJECTED");
        assertThat(payload.get("approvedAt").isNull()).isTrue();
    }

    // 결과 불명은 실패가 아니다 — 리스를 쥔 채 남고(복구 NV-102) 이벤트를 보내지 않는다
    @Test
    void unknownConfirmStaysProcessingWithoutEvent() throws Exception {
        tossAnswers(new TossCommandResult.Unknown(UnknownReason.TIMEOUT, null));

        confirm(opened, target, AMOUNT)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("PENDING"));

        assertThat(transaction()).containsEntry("status", "PROCESSING").containsEntry("last_error_code", "TIMEOUT");
        assertThat(settledEvents()).isZero();
    }

    @Test
    void transientConfirmIsRescheduledWithoutEvent() throws Exception {
        tossAnswers(new TossCommandResult.Transient("PROVIDER_ERROR", "일시 오류"));

        confirm(opened, target, AMOUNT)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("PENDING"));

        assertThat(transaction()).containsEntry("status", "RETRY_SCHEDULED");
        assertThat(settledEvents()).isZero();
    }

    // D15: 토스가 다른 금액으로 승인했다고 하면 승인으로 반영하지 않는다
    @Test
    void approvedWithDifferentAmountStaysUnknown() throws Exception {
        tossAnswers(done(AMOUNT + 1));

        confirm(opened, target, AMOUNT)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("PENDING"));

        assertThat(transaction()).containsEntry("status", "PROCESSING").containsEntry("last_error_code", "AMOUNT_MISMATCH");
        assertThat(paymentsOfTarget()).isZero();
        assertThat(settledEvents()).isZero();
    }

    // NV-101 완료 조건(D15): PENDING 금액 ≠ 주문 총액이면 승인 거절. 결제창을 다른 금액으로 열었다는 뜻이다
    @Test
    void rejectsWhenPendingAmountDiffersFromCallerAmount() throws Exception {
        confirm(opened, target, AMOUNT - 1000)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PAYMENT_AMOUNT_MISMATCH"));

        assertNotStarted();
    }

    // 남의 결제창 번호 — 없는 번호와 같은 응답(떠볼 수 없게)
    @Test
    void rejectsOtherTargetsAttemptAsNotFound() throws Exception {
        confirm(opened, PaymentFixtures.newOrderTarget(), AMOUNT)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PAYMENT_ATTEMPT_NOT_FOUND"));

        assertNotStarted();
    }

    @Test
    void rejectsUnknownProviderOrderId() throws Exception {
        perform(UUID.randomUUID().toString(), body("ORDER", target.id(), paymentKey, AMOUNT))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PAYMENT_ATTEMPT_NOT_FOUND"));

        verify(toss, never()).confirm(any(), any());
    }

    // 결과를 알릴 이벤트가 없는 대상은 시작하지 않는다
    @Test
    void rejectsDrawEntryTarget() throws Exception {
        PaymentTarget entry = PaymentFixtures.newDrawEntryTarget();
        PaymentTransaction entryAttempt = prepareService.open(entry, Money.won(1000));

        confirm(entryAttempt, entry, 1000)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("PAYMENT_TARGET_UNSUPPORTED"));

        verify(toss, never()).confirm(any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {".", "..", "tgen_bad\nline"})
    void rejectsPaymentKeyBreakingProviderRules(String badKey) throws Exception {
        perform(opened.providerOrderId().value(), body("ORDER", target.id(), badKey, AMOUNT))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        assertNotStarted();
    }

    @Test
    void rejectsMalformedProviderOrderId() throws Exception {
        perform("bad!", body("ORDER", target.id(), paymentKey, AMOUNT))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    // D13: 같은 대상에 진행 중인 거래가 있으면 두 번째를 시작하지 않는다 — 그 거래의 결과 이벤트가 간다
    @Test
    void secondAttemptWhileFirstInFlightIsPending() throws Exception {
        tossAnswers(new TossCommandResult.Unknown(UnknownReason.TIMEOUT, null));
        confirm(opened, target, AMOUNT).andExpect(status().isOk());
        PaymentTransaction second = prepareService.open(target, Money.won(AMOUNT));

        confirm(second, target, AMOUNT)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("PENDING"));

        verify(toss, times(1)).confirm(any(), any());
        assertThat(statusOf(second)).isEqualTo("PENDING");
    }

    // 같은 결제창 번호로 다시 부르면 토스를 부르지 않고 지금 결과를 돌려준다(호출자가 응답을 잃었을 때 회수)
    @Test
    void repeatedConfirmReturnsCurrentResultWithoutCallingToss() throws Exception {
        tossAnswers(done(AMOUNT));
        confirm(opened, target, AMOUNT).andExpect(status().isOk());

        confirm(opened, target, AMOUNT)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("APPROVED"));

        verify(toss, times(1)).confirm(any(), any());
        assertThat(settledEvents()).isOne();
    }

    /*
     * 같은 결제창으로 승인 두 건이 동시에 온다(더블 클릭 · 두 인스턴스). 한쪽만 시작해 토스를 부르고, 다른 쪽은 토스를 부르지 않고
     * PENDING 이다. 토스는 진 쪽 응답이 끝난 뒤에 답하게 해 겹침을 확실히 만든다. 같은 행을 동시에 시작하는 원장 수준의 경합은
     * PaymentLedgerConcurrencyTest 가 본다.
     */
    @Test
    void concurrentConfirmsOfSameAttemptCallTossOnce() throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch loserDone = new CountDownLatch(1);
        given(toss.confirm(any(), any())).willAnswer(invocation -> {
            loserDone.await(10, TimeUnit.SECONDS);
            return done(AMOUNT);
        });
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                results.add(pool.submit(() -> {
                    go.await();
                    String body = confirm(opened, target, AMOUNT).andExpect(status().isOk())
                            .andReturn().getResponse().getContentAsString();
                    if (JsonPath.<String>read(body, "$.data.result").equals("PENDING")) {
                        loserDone.countDown();
                    }
                    return JsonPath.read(body, "$.data.result");
                }));
            }
            go.countDown();

            List<String> answers = new ArrayList<>();
            for (Future<String> result : results) {
                answers.add(result.get(20, TimeUnit.SECONDS));
            }

            assertThat(answers).containsExactlyInAnyOrder("APPROVED", "PENDING");
        } finally {
            pool.shutdownNow();
        }
        verify(toss, times(1)).confirm(any(), any());
        assertThat(settledEvents()).isOne();
        assertThat(paymentsOfTarget()).isOne();
    }

    @Test
    void repeatedConfirmAfterDeclineReturnsDeclineReason() throws Exception {
        tossAnswers(new TossCommandResult.Rejected("NOT_FOUND_PAYMENT_SESSION", "만료"));
        confirm(opened, target, AMOUNT).andExpect(status().isOk());

        confirm(opened, target, AMOUNT)
                .andExpect(jsonPath("$.data.result").value("DECLINED"))
                .andExpect(jsonPath("$.data.declineReason").value("PAYMENT_EXPIRED"));
    }

    @Test
    void repeatedConfirmWhileProcessingIsPending() throws Exception {
        tossAnswers(new TossCommandResult.Unknown(UnknownReason.TIMEOUT, null));
        confirm(opened, target, AMOUNT).andExpect(status().isOk());

        confirm(opened, target, AMOUNT).andExpect(jsonPath("$.data.result").value("PENDING"));

        verify(toss, times(1)).confirm(any(), any());
    }

    // 토스를 기다리는 사이 리스가 끝나 다른 작업자가 집어 갔다 — 반영하지 않고 PENDING(결과는 리스를 쥔 쪽이 확정한다)
    @Test
    void lostLeaseLeavesResultToHolder() throws Exception {
        given(toss.confirm(any(), any())).willAnswer(invocation -> {
            jdbcTemplate.update("UPDATE payment_transactions SET lease_token = ? WHERE id = ?",
                    UUID.randomUUID().toString(), opened.id());
            return done(AMOUNT);
        });

        confirm(opened, target, AMOUNT)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("PENDING"));

        assertThat(transaction()).containsEntry("status", "PROCESSING");
        assertThat(paymentsOfTarget()).isZero();
        assertThat(settledEvents()).isZero();
    }

    // D13 의 다른 거래가 이미 성공했으면 승인으로 답한다 — 그 결과 이벤트를 대상이 놓쳤어도 재요청으로 회수된다
    @Test
    void secondAttemptAfterFirstSucceededIsApproved() throws Exception {
        tossAnswers(done(AMOUNT));
        confirm(opened, target, AMOUNT).andExpect(jsonPath("$.data.result").value("APPROVED"));
        PaymentTransaction second = prepareService.open(target, Money.won(AMOUNT));

        confirm(second, target, AMOUNT)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("APPROVED"));

        verify(toss, times(1)).confirm(any(), any());
        assertThat(statusOf(second)).isEqualTo("PENDING");
    }

    // 시작 금지: 호출자가 결제할 수 없다고 판정한 대상 — 시작 전 거래는 시작하지 않고 PENDING, 시작된 거래는 결과를 회수한다
    @Test
    void recoverOnlyDoesNotStartPendingAttempt() throws Exception {
        recoverOnly(opened, target)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("PENDING"));

        assertNotStarted();
    }

    @Test
    void recoverOnlyReturnsResultOfStartedAttempt() throws Exception {
        tossAnswers(new TossCommandResult.Rejected("REJECT_CARD_PAYMENT", "한도초과"));
        confirm(opened, target, AMOUNT).andExpect(status().isOk());

        recoverOnly(opened, target)
                .andExpect(jsonPath("$.data.result").value("DECLINED"))
                .andExpect(jsonPath("$.data.declineReason").value("CARD_REJECTED"));

        verify(toss, times(1)).confirm(any(), any());
    }

    // 만료된 결제창: 토스를 부르지 않고 "결제창 만료" 거절. 결과 회수(startAllowed=false)도 같다 — 승인 중인 주문을 되돌리는 답이다
    @Test
    void expiredAttemptIsDeclinedAsExpiredWithoutCallingToss() throws Exception {
        jdbcTemplate.update("UPDATE payment_transactions SET created_at = UTC_TIMESTAMP(6) - INTERVAL ? SECOND WHERE id = ?",
                PendingCaptureExpiry.OPENED_FOR.plusMinutes(1).toSeconds(), opened.id());
        expiry.expireDue();

        confirm(opened, target, AMOUNT)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("DECLINED"))
                .andExpect(jsonPath("$.data.declineReason").value("PAYMENT_EXPIRED"));
        recoverOnly(opened, target)
                .andExpect(jsonPath("$.data.result").value("DECLINED"))
                .andExpect(jsonPath("$.data.declineReason").value("PAYMENT_EXPIRED"));
        verify(toss, never()).confirm(any(), any());
        assertThat(statusOf(opened)).isEqualTo("EXPIRED");
    }

    /*
     * 동시성: 호출자가 승인 중으로 바꾸기 직전의 확인(reserve)은 결제창을 확보해 만료를 뒤로 민다 — 확인과 그 전환 사이에 만료가 끼어
     * 만료 결과가 대상에 먼저 도착해 버려지는 경합을 막는다. 만료가 먼저였으면 확인이 그것을 본다.
     */
    @Test
    void reservingCheckPostponesExpiry() throws Exception {
        ageCreatedAt(PendingCaptureExpiry.OPENED_FOR.plusMinutes(1));

        reserveCheck(opened, target)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("PENDING"));
        expiry.expireDue();

        assertThat(statusOf(opened)).isEqualTo("PENDING");
        assertThat(settledEvents()).isZero();
    }

    @Test
    void reservingCheckAfterExpirySeesExpired() throws Exception {
        ageCreatedAt(PendingCaptureExpiry.OPENED_FOR.plusMinutes(1));
        expiry.expireDue();

        reserveCheck(opened, target)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("DECLINED"))
                .andExpect(jsonPath("$.data.declineReason").value("PAYMENT_EXPIRED"));
    }

    // 결과 회수(reserve 없음)는 결제창을 확보하지 않는다 — 결제할 수 없게 된 주문의 재요청이 만료를 미루지 않게
    @Test
    void recoverOnlyDoesNotPostponeExpiry() throws Exception {
        ageCreatedAt(PendingCaptureExpiry.OPENED_FOR.plusMinutes(1));

        recoverOnly(opened, target).andExpect(jsonPath("$.data.result").value("PENDING"));
        expiry.expireDue();

        assertThat(statusOf(opened)).isEqualTo("EXPIRED");
    }

    @Test
    void reserveWithStartAllowedIsRejected() throws Exception {
        perform(opened.providerOrderId().value(), jsonMapper.writeValueAsString(Map.of("targetType", "ORDER",
                "targetId", target.id(), "paymentKey", paymentKey, "amount", AMOUNT, "startAllowed", true,
                "reserve", true)))
                .andExpect(status().isBadRequest());
        assertNotStarted();
    }

    @Test
    void recoverOnlyForOtherTargetIsNotFound() throws Exception {
        recoverOnly(opened, PaymentFixtures.newOrderTarget())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PAYMENT_ATTEMPT_NOT_FOUND"));
    }

    @Test
    void requiresStartAllowedFlag() throws Exception {
        perform(opened.providerOrderId().value(), jsonMapper.writeValueAsString(Map.of("targetType", "ORDER",
                "targetId", target.id(), "paymentKey", paymentKey, "amount", AMOUNT)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        assertNotStarted();
    }

    @Test
    void requiresBearerToken() throws Exception {
        mockMvc.perform(post("/internal/payment-attempts/{id}/confirm", opened.providerOrderId().value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("ORDER", target.id(), paymentKey, AMOUNT)))
                .andExpect(status().isUnauthorized());

        assertNotStarted();
    }

    private void tossAnswers(TossCommandResult result) {
        given(toss.confirm(any(), any())).willReturn(result);
    }

    private TossCommandResult done(long totalAmount) {
        return new TossCommandResult.Succeeded(new TossPayment(paymentKey, opened.providerOrderId().value(),
                TossPaymentStatus.DONE, totalAmount, totalAmount, "카드", APPROVED_AT, "txkey", null));
    }

    private ResultActions confirm(PaymentTransaction attempt, PaymentTarget as, long amount) throws Exception {
        return perform(attempt.providerOrderId().value(), body(as.type().name(), as.id(), paymentKey, amount));
    }

    private ResultActions recoverOnly(PaymentTransaction attempt, PaymentTarget as) throws Exception {
        return perform(attempt.providerOrderId().value(), body(as.type().name(), as.id(), paymentKey, AMOUNT, false));
    }

    private ResultActions reserveCheck(PaymentTransaction attempt, PaymentTarget as) throws Exception {
        return perform(attempt.providerOrderId().value(), jsonMapper.writeValueAsString(Map.of("targetType",
                as.type().name(), "targetId", as.id(), "paymentKey", paymentKey, "amount", AMOUNT, "startAllowed", false,
                "reserve", true)));
    }

    private void ageCreatedAt(Duration age) {
        jdbcTemplate.update("UPDATE payment_transactions SET created_at = UTC_TIMESTAMP(6) - INTERVAL ? SECOND WHERE id = ?",
                age.toSeconds(), opened.id());
    }

    private ResultActions perform(String providerOrderId, String json) throws Exception {
        return mockMvc.perform(post("/internal/payment-attempts/{id}/confirm", providerOrderId)
                .header(BearerTokens.HEADER, BearerTokens.value(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    private String body(String targetType, Long targetId, String key, long amount) {
        return body(targetType, targetId, key, amount, true);
    }

    private String body(String targetType, Long targetId, String key, long amount, boolean startAllowed) {
        return jsonMapper.writeValueAsString(Map.of("targetType", targetType, "targetId", targetId,
                "paymentKey", key, "amount", amount, "startAllowed", startAllowed));
    }

    private void assertNotStarted() {
        verify(toss, never()).confirm(any(), any());
        assertThat(statusOf(opened)).isEqualTo("PENDING");
        assertThat(settledEvents()).isZero();
    }

    private Map<String, Object> transaction() {
        return jdbcTemplate.queryForMap("""
                SELECT status, attempt_count, last_error_code FROM payment_transactions WHERE id = ?
                """, opened.id());
    }

    private String statusOf(PaymentTransaction attempt) {
        return jdbcTemplate.queryForObject("SELECT status FROM payment_transactions WHERE id = ?", String.class,
                attempt.id());
    }

    private int paymentsOfTarget() {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM payments WHERE target_type = 'ORDER' AND target_id = ?
                """, Integer.class, target.id());
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
        assertThat(payloads.getFirst()).doesNotContain(paymentKey);
        return jsonMapper.readTree(payloads.getFirst());
    }
}
