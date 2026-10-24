package com.grandis.nova.payment.deadletter;

import com.grandis.nova.common.UuidBinary;
import com.grandis.nova.common.security.RevocationChecker;
import com.grandis.nova.common.sqs.testing.TestQueues;
import com.grandis.nova.payment.ClaimedTransaction;
import com.grandis.nova.payment.PaymentLedger;
import com.grandis.nova.payment.client.toss.TossPaymentClient;
import com.grandis.nova.payment.deadletter.application.DeadLetterAdminService;
import com.grandis.nova.payment.domain.enums.TransactionType;
import com.grandis.nova.payment.domain.model.Outcome;
import com.grandis.nova.payment.domain.model.PaymentTransaction;
import com.grandis.nova.payment.domain.repository.PaymentTransactionReader;
import com.grandis.nova.payment.support.PaymentFixtures;
import com.grandis.nova.payment.support.SqsIntegrationTest;
import com.grandis.nova.payment.vo.Money;
import com.grandis.nova.payment.vo.PaymentTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 실제 SQS(Floci)로 처리 실패 → DLQ → DB → 되돌리기 → 결과 기록까지 잇는다.
 * 처리 실패는 결제 기록이 없는 주문의 환불 요청으로 만든다 — 결제 기록이 생기면(고침) 되돌린 요청이 환불을 연다.
 */
@SqsIntegrationTest
class DeadLetterSqsFlowTest {

    static final Duration TIMEOUT = Duration.ofSeconds(40);
    static final Money AMOUNT = Money.won(15000);

    @Autowired
    TestQueues queues;

    @Autowired
    DeadLetterAdminService adminService;

    @Autowired
    PaymentLedger ledger;

    @Autowired
    PaymentTransactionReader transactions;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @MockitoBean
    RevocationChecker revocationChecker;

    @MockitoBean
    TossPaymentClient toss;

    PaymentTarget target;
    String body;

    @BeforeEach
    void setUp() {
        target = PaymentFixtures.newOrderTarget();
        body = """
                {"eventId":"%s","eventType":"ORDER_REFUND_REQUESTED","aggregateType":"ORDER","aggregateId":"%s",\
                "occurredAt":"2026-10-04T01:00:00Z","payload":{"amount":15000}}""".formatted(UUID.randomUUID(), target.id());
    }

    @Test
    void 고친_뒤_되돌리면_처리되고_DLQ_행은_SUCCEEDED_다() {
        queues.send("payment-events", body);
        UUID id = awaitOpenRow();
        assertThat(jdbcTemplate.queryForObject("SELECT BIN_TO_UUID(aggregate_id) FROM payment_dead_letter_events WHERE id = ?",
                String.class, (Object) UuidBinary.toBytes(id))).isEqualTo(target.id().toString());

        paid();
        adminService.redrive(id, "admin");

        await().atMost(TIMEOUT).until(() -> "SUCCEEDED".equals(status(id)));
        assertThat(transactions.findTransactionsByTarget(target))
                .anyMatch(transaction -> transaction.type() == TransactionType.REFUND);
    }

    @Test
    void 고치지_않고_되돌리면_또_DLQ_로_와_새_행이_앞선_행을_가리킨다() {
        queues.send("payment-events", body);
        UUID id = awaitOpenRow();

        adminService.redrive(id, "admin");

        await().atMost(TIMEOUT).until(() -> "REDRIVE_FAILED".equals(status(id)));
        await().atMost(TIMEOUT).until(() -> jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_dead_letter_events WHERE redriven_from_id = ? AND status = 'OPEN'",
                Long.class, (Object) UuidBinary.toBytes(id)) == 1);
    }

    private void paid() {
        PaymentTransaction pending = transactionTemplate.execute(s -> ledger.openCapture(target, AMOUNT));
        ClaimedTransaction started = transactionTemplate.execute(s -> ledger.start(pending, target,
                PaymentFixtures.newProviderPayment(), AMOUNT)).orElseThrow();
        transactionTemplate.executeWithoutResult(s -> ledger.resolve(started,
                new Outcome.Confirmed(Instant.parse("2026-10-04T01:00:00Z"))));
    }

    private UUID awaitOpenRow() {
        await().alias("처리 실패 → DLQ → DB").atMost(TIMEOUT).until(() -> jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_dead_letter_events WHERE body = ? AND status = 'OPEN'", Long.class, body) == 1);
        return jdbcTemplate.queryForObject("SELECT id FROM payment_dead_letter_events WHERE body = ? AND status = 'OPEN'",
                (rs, rowNum) -> UuidBinary.fromBytes(rs.getBytes(1)), body);
    }

    private String status(UUID id) {
        return jdbcTemplate.queryForObject("SELECT status FROM payment_dead_letter_events WHERE id = ?", String.class,
                (Object) UuidBinary.toBytes(id));
    }
}
