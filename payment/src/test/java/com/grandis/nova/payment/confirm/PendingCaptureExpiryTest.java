package com.grandis.nova.payment.confirm;

import com.grandis.nova.common.UuidBinary;
import com.grandis.nova.common.security.RevocationChecker;
import com.grandis.nova.common.testing.Concurrently;
import com.grandis.nova.payment.ClaimedTransaction;
import com.grandis.nova.payment.PaymentLedger;
import com.grandis.nova.payment.client.toss.TossPaymentClient;
import com.grandis.nova.payment.domain.enums.TransactionStatus;
import com.grandis.nova.payment.domain.model.PaymentTransaction;
import com.grandis.nova.payment.domain.repository.PaymentTransactionReader;
import com.grandis.nova.payment.support.PaymentFixtures;
import com.grandis.nova.payment.support.PaymentIntegrationTest;
import com.grandis.nova.payment.vo.Money;
import com.grandis.nova.payment.vo.PaymentTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 시작하지 않은 결제창 만료. 연 시각은 행의 created_at 을 당겨 만든다. 다른 테스트의 오래된 PENDING 도 함께 닫힐 수 있다 —
 * 이 테스트는 자기 행만 본다.
 */
@PaymentIntegrationTest
@AutoConfigureMockMvc
class PendingCaptureExpiryTest {

    static final Money AMOUNT = Money.won(15000);
    static final Duration PAST_DUE = PendingCaptureExpiry.OPENED_FOR.plusMinutes(1);

    @Autowired
    PendingCaptureExpiry expiry;

    @Autowired
    CaptureSettlement settlement;

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

    @BeforeEach
    void setUp() {
        target = PaymentFixtures.newOrderTarget();
    }

    @Test
    void unsentCapturePastDueIsExpiredAndDeclinedAsExpired() {
        PaymentTransaction pending = open(target);
        age(pending, PAST_DUE);

        expiry.expireDue();

        PaymentTransaction expired = transactions.findById(pending.id()).orElseThrow();
        assertThat(expired.status()).isEqualTo(TransactionStatus.EXPIRED);
        assertThat(expired.finishedAt()).isNotNull();
        JsonNode payload = settledPayload(target);
        assertThat(payload.get("result").asString()).isEqualTo("DECLINED");
        assertThat(payload.get("declineReason").asString()).isEqualTo("PAYMENT_EXPIRED");
        assertThat(payload.get("providerOrderId").asString()).isEqualTo(pending.providerOrderId().value());
        assertThat(payload.get("amount").asLong()).isEqualTo(15000);
    }

    // 토스가 아직 승인할 수 있는 결제창(결제 객체 수명 30분 + 여유)은 닫지 않는다
    @Test
    void captureWithinOpenWindowIsKept() {
        PaymentTransaction pending = open(target);
        age(pending, PendingCaptureExpiry.OPENED_FOR.minusMinutes(1));

        expiry.expireDue();

        assertThat(transactions.findById(pending.id()).orElseThrow().status()).isEqualTo(TransactionStatus.PENDING);
        assertThat(settledEvents(target)).isZero();
    }

    // 한 번이라도 보낸 거래는 결제사가 처리했을 수 있다 — 아무리 오래돼도 만료하지 않는다(복구가 확정한다)
    @Test
    void startedCaptureIsNeverExpired() {
        PaymentTransaction pending = open(target);
        transactionTemplate.execute(s -> ledger.start(pending, target, PaymentFixtures.newProviderPayment(), AMOUNT))
                .orElseThrow();
        age(pending, PAST_DUE);

        expiry.expireDue();

        assertThat(transactions.findById(pending.id()).orElseThrow().status()).isEqualTo(TransactionStatus.PROCESSING);
        assertThat(settledEvents(target)).isZero();
    }

    // 드로우 응모의 결제창도 만료를 알린다 — 응모 대상의 이벤트(DRAW_ENTRY_PAYMENT_SETTLED)로. 승인 중인 응모가 이걸로 결제 대기로 풀린다
    @Test
    void drawEntryCaptureExpiryIsNotifiedAsDrawEntry() {
        PaymentTarget entry = PaymentFixtures.newDrawEntryTarget();
        PaymentTransaction pending = open(entry);
        age(pending, PAST_DUE);

        expiry.expireDue();

        assertThat(transactions.findById(pending.id()).orElseThrow().status()).isEqualTo(TransactionStatus.EXPIRED);
        List<String> payloads = jdbcTemplate.queryForList("""
                SELECT payload FROM payment_outbox_events
                 WHERE aggregate_type = 'DRAW_ENTRY' AND aggregate_id = ? AND event_type = 'DRAW_ENTRY_PAYMENT_SETTLED'
                """, String.class, UuidBinary.toBytes(entry.id()));
        assertThat(payloads).hasSize(1);
        JsonNode payload = jsonMapper.readTree(payloads.getFirst());
        assertThat(payload.get("result").asString()).isEqualTo("DECLINED");
        assertThat(payload.get("declineReason").asString()).isEqualTo("PAYMENT_EXPIRED");
        assertThat(payload.get("providerOrderId").asString()).isEqualTo(pending.providerOrderId().value());
        assertThat(settledEvents(entry)).as("주문 이벤트로는 나가지 않는다").isZero();
    }

    // 시작과 만료가 겹치면 한쪽만 된다. 만료가 이기면 알림이 가고, 시작이 이기면 알림 없이 진행 중이다
    @RepeatedTest(5)
    void startAndExpireRacingLeaveOneOutcome() throws Exception {
        PaymentTransaction pending = open(target);
        age(pending, PAST_DUE);

        List<Concurrently.Outcome<Object>> outcomes = Concurrently.run(2, i -> () -> i == 0
                ? transactionTemplate.execute(s -> ledger.start(pending, target, PaymentFixtures.newProviderPayment(),
                AMOUNT))
                : settlement.expire(pending, PendingCaptureExpiry.OPENED_FOR));

        assertThat(outcomes).allSatisfy(o -> assertThat(o.error()).isNull());
        boolean started = ((Optional<?>) outcomes.get(0).value()).map(ClaimedTransaction.class::cast).isPresent();
        boolean expired = ((Optional<?>) outcomes.get(1).value()).isPresent();
        assertThat(started).isNotEqualTo(expired);
        TransactionStatus status = transactions.findById(pending.id()).orElseThrow().status();
        assertThat(status).isEqualTo(started ? TransactionStatus.PROCESSING : TransactionStatus.EXPIRED);
        assertThat(settledEvents(target)).isEqualTo(expired ? 1 : 0);
    }

    // 확보는 만료를 미룰 뿐 없애지 않는다 — 확보한 결제창도 확보 시각부터 기준이 지나면 만료되고 거절이 한 번 간다
    @Test
    void reservedCaptureStillExpiresAfterReservation() {
        PaymentTransaction pending = open(target);
        Boolean reserved = transactionTemplate.execute(s -> ledger.reserve(pending));
        assertThat(reserved).isTrue();
        expiry.expireDue();
        assertThat(transactions.findById(pending.id()).orElseThrow().status()).isEqualTo(TransactionStatus.PENDING);

        jdbcTemplate.update("UPDATE payment_transactions SET reserved_at = UTC_TIMESTAMP(6) - INTERVAL ? SECOND WHERE id = ?",
                PAST_DUE.toSeconds(), UuidBinary.toBytes(pending.id()));
        expiry.expireDue();

        assertThat(transactions.findById(pending.id()).orElseThrow().status()).isEqualTo(TransactionStatus.EXPIRED);
        assertThat(settledEvents(target)).isEqualTo(1);
    }

    // 확보와 만료가 겹치면 한쪽만 된다. 확보가 이기면 만료되지 않고(기준을 다시 잰다), 만료가 이기면 확보는 0행이다
    @RepeatedTest(5)
    void reserveAndExpireRacingLeaveOneOutcome() throws Exception {
        PaymentTransaction pending = open(target);
        age(pending, PAST_DUE);

        List<Concurrently.Outcome<Object>> outcomes = Concurrently.run(2, i -> () -> i == 0
                ? transactionTemplate.execute(s -> ledger.reserve(pending))
                : settlement.expire(pending, PendingCaptureExpiry.OPENED_FOR));

        assertThat(outcomes).allSatisfy(o -> assertThat(o.error()).isNull());
        boolean reserved = (Boolean) outcomes.get(0).value();
        boolean expired = ((Optional<?>) outcomes.get(1).value()).isPresent();
        assertThat(reserved).isNotEqualTo(expired);
        assertThat(transactions.findById(pending.id()).orElseThrow().status())
                .isEqualTo(reserved ? TransactionStatus.PENDING : TransactionStatus.EXPIRED);
    }

    private PaymentTransaction open(PaymentTarget of) {
        return transactionTemplate.execute(s -> ledger.openCapture(of, AMOUNT));
    }

    private void age(PaymentTransaction transaction, Duration age) {
        jdbcTemplate.update("UPDATE payment_transactions SET created_at = UTC_TIMESTAMP(6) - INTERVAL ? SECOND WHERE id = ?",
                age.toSeconds(), UuidBinary.toBytes(transaction.id()));
    }

    private int settledEvents(PaymentTarget of) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM payment_outbox_events
                 WHERE aggregate_type = 'ORDER' AND aggregate_id = ? AND event_type = 'ORDER_PAYMENT_SETTLED'
                """, Integer.class, UuidBinary.toBytes(of.id()));
    }

    private JsonNode settledPayload(PaymentTarget of) {
        List<String> payloads = jdbcTemplate.queryForList("""
                SELECT payload FROM payment_outbox_events
                 WHERE aggregate_type = 'ORDER' AND aggregate_id = ? AND event_type = 'ORDER_PAYMENT_SETTLED'
                """, String.class, UuidBinary.toBytes(of.id()));
        assertThat(payloads).hasSize(1);
        return jsonMapper.readTree(payloads.getFirst());
    }
}
