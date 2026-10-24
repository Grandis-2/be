package com.grandis.nova.payment.deadletter;

import com.grandis.nova.common.UuidBinary;
import com.grandis.nova.common.security.RevocationChecker;
import com.grandis.nova.common.testing.Concurrently;
import com.grandis.nova.common.testing.Concurrently.Outcome;
import com.grandis.nova.payment.deadletter.domain.DeadLetterEvent;
import com.grandis.nova.payment.deadletter.domain.DeadLetterEventRepository;
import com.grandis.nova.payment.deadletter.domain.DeadLetterStatus;
import com.grandis.nova.payment.deadletter.domain.DeadLetterWaitingQuery;
import com.grandis.nova.payment.deadletter.domain.DeadLetterWaitingQuery.WaitingGroup;
import com.grandis.nova.payment.deadletter.domain.FailureReason;
import com.grandis.nova.payment.support.PaymentIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** DLQ 적재와 되돌린 메시지의 결과 기록. DB 를 공유하므로 단정은 이 테스트가 만든 행으로만 한다. */
@PaymentIntegrationTest
class DeadLettersTest {

    static final String QUEUE = "payment-events";

    @Autowired
    DeadLetters deadLetters;

    @Autowired
    DeadLetterEventRepository events;

    @Autowired
    DeadLetterWaitingQuery waitingQuery;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @MockitoBean
    RevocationChecker revocationChecker;

    @Test
    void 봉투를_읽어_주문을_붙여_OPEN_으로_쌓는다() {
        UUID orderId = UUID.randomUUID();
        String messageId = unique();
        Instant sentAt = Instant.parse("2026-10-04T01:00:00Z");

        assertThat(deadLetters.record(new IncomingDeadLetter(QUEUE, messageId, refundRequested(orderId), 5, sentAt,
                null))).isTrue();

        DeadLetterEvent event = find(messageId);
        assertThat(event.getStatus()).isEqualTo(DeadLetterStatus.OPEN);
        assertThat(event.getFailureReason()).isEqualTo(FailureReason.PROCESSING_FAILED);
        assertThat(event.getEventType()).isEqualTo("ORDER_REFUND_REQUESTED");
        assertThat(event.getAggregateType()).isEqualTo("ORDER");
        assertThat(event.getOrderId()).isEqualTo(orderId);
        assertThat(event.getReceiveCount()).isEqualTo(5);
        assertThat(event.getSentAt()).isEqualTo(sentAt);
    }

    @Test
    void 주문_이벤트가_아니면_주문을_붙이지_않고_되돌려도_같은_결과라_UNREADABLE_BODY_다() {
        String messageId = unique();

        deadLetters.record(new IncomingDeadLetter(QUEUE, messageId, """
                {"eventId":"%s","eventType":"ORDER_REFUND_REQUESTED","aggregateType":"DRAW_ENTRY",
                 "aggregateId":"%s","occurredAt":"2026-10-04T01:00:00Z","payload":{"amount":15000}}
                """.formatted(UUID.randomUUID(), UUID.randomUUID()), 5, null, null));

        DeadLetterEvent event = find(messageId);
        assertThat(event.getAggregateId()).isNotNull();
        assertThat(event.getOrderId()).isNull();
        assertThat(event.getEventType()).isEqualTo("ORDER_REFUND_REQUESTED");
        assertThat(event.getFailureReason()).isEqualTo(FailureReason.UNREADABLE_BODY);
    }

    @Test
    void 읽을_수_없는_원문도_그대로_쌓는다() {
        String messageId = unique();

        deadLetters.record(new IncomingDeadLetter(QUEUE, messageId, "not-json", 5, null, null));

        DeadLetterEvent event = find(messageId);
        assertThat(event.getFailureReason()).isEqualTo(FailureReason.UNREADABLE_BODY);
        assertThat(event.getBody()).isEqualTo("not-json");
        assertThat(event.getOrderId()).isNull();
    }

    @Test
    void 지우기_전에_죽어_다시_받은_메시지는_한_번만_쌓는다() {
        String messageId = unique();
        IncomingDeadLetter incoming = new IncomingDeadLetter(QUEUE, messageId, "not-json", 5, null, null);

        assertThat(deadLetters.record(incoming)).isTrue();
        assertThat(deadLetters.record(incoming)).isFalse();

        assertThat(count("SELECT COUNT(*) FROM payment_dead_letter_events WHERE message_id = ?", messageId))
                .isEqualTo(1);
    }

    /** DLQ 소비기 여럿이 같은 메시지를 겹쳐 받은 경우. UNIQUE 가 한 행만 남기고, 진 쪽은 예외나 false 로 끝난다. */
    @Test
    void 같은_메시지를_동시에_적재해도_한_행만_남는다() throws Exception {
        String messageId = unique();
        IncomingDeadLetter incoming = new IncomingDeadLetter(QUEUE, messageId, "not-json", 5, null, null);

        List<Outcome<Boolean>> outcomes = Concurrently.run(4, i -> () -> deadLetters.record(incoming));

        assertThat(count("SELECT COUNT(*) FROM payment_dead_letter_events WHERE message_id = ?", messageId))
                .isEqualTo(1);
        assertThat(outcomes.stream().filter(outcome -> outcome.succeeded() && outcome.value())).hasSize(1);
        assertThat(outcomes).as("진 쪽은 이미 쌓임(false)이거나 UNIQUE 충돌이다 — 다른 예외는 안 된다")
                .allSatisfy(outcome -> {
                    if (!outcome.succeeded()) {
                        assertThat(outcome.error()).isInstanceOf(DataIntegrityViolationException.class)
                                .hasStackTraceContaining("uq_payment_dead_letter_message");
                    }
                });
    }

    @Test
    void 되돌린_메시지가_또_오면_앞선_행을_REDRIVE_FAILED_로_바꾸고_새_행이_가리킨다() {
        UUID previous = redriven(unique());
        String messageId = unique();

        deadLetters.record(new IncomingDeadLetter(QUEUE, messageId, "not-json", 5, null, previous));

        assertThat(find(messageId).getRedrivenFromId()).isEqualTo(previous);
        DeadLetterEvent failed = events.findById(previous).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(DeadLetterStatus.REDRIVE_FAILED);
        assertThat(failed.getOutcomeAt()).isNotNull();
    }

    @Test
    void 앞선_행이_없는_속성이면_잇지_않고_쌓는다() {
        String messageId = unique();

        deadLetters.record(new IncomingDeadLetter(QUEUE, messageId, "not-json", 5, null, UUID.randomUUID()));

        assertThat(find(messageId).getRedrivenFromId()).isNull();
    }

    @Test
    void 되돌린_메시지가_처리되면_SUCCEEDED_이고_보냄_기록보다_먼저여도_남는다() {
        UUID redriven = redriven(unique());
        UUID stillSending = claimed(unique());

        deadLetters.markRedriveSucceeded(redriven);
        deadLetters.markRedriveSucceeded(stillSending);

        assertThat(events.findById(redriven).orElseThrow().getStatus()).isEqualTo(DeadLetterStatus.SUCCEEDED);
        DeadLetterEvent early = events.findById(stillSending).orElseThrow();
        assertThat(early.getStatus()).isEqualTo(DeadLetterStatus.SUCCEEDED);
        assertThat(early.getRedrivenAt()).as("결과 시각으로 채운다").isNotNull();
    }

    @Test
    void 되돌리지_않은_행에는_처리_결과를_남기지_않는다() {
        String messageId = unique();
        deadLetters.record(new IncomingDeadLetter(QUEUE, messageId, "not-json", 5, null, null));
        UUID id = find(messageId).getId();

        deadLetters.markRedriveSucceeded(id);

        assertThat(events.findById(id).orElseThrow().getStatus()).isEqualTo(DeadLetterStatus.OPEN);
    }

    @Test
    void 보내다_멈춘_REDRIVING_은_1분이_지나야_되돌리기_대기로_센다() {
        UUID recent = claimed(unique());
        UUID stale = claimed(unique());
        jdbcTemplate.update("UPDATE payment_dead_letter_events SET redrive_started_at = redrive_started_at - INTERVAL 2 MINUTE"
                + " WHERE id = ?", (Object) UuidBinary.toBytes(stale));
        Instant staleBefore = Instant.now().minus(DeadLetterStatus.STALE_REDRIVE);

        List<UUID> waiting = events.findWaitingIds(null, FailureReason.UNREADABLE_BODY, staleBefore, Integer.MAX_VALUE);

        assertThat(waiting).contains(stale).doesNotContain(recent);
        assertThat(events.findById(stale).orElseThrow().waitingForRedrive(staleBefore)).isTrue();
        assertThat(events.findById(recent).orElseThrow().waitingForRedrive(staleBefore)).isFalse();
        long before = countWaiting(staleBefore);
        jdbcTemplate.update("UPDATE payment_dead_letter_events SET redrive_started_at = redrive_started_at - INTERVAL 2 MINUTE"
                + " WHERE id = ?", (Object) UuidBinary.toBytes(recent));
        assertThat(countWaiting(staleBefore)).isEqualTo(before + 1);
    }

    @Test
    void 보냄_기록_전에_또_DLQ_로_와도_앞선_행은_REDRIVE_FAILED_다() {
        UUID stillSending = claimed(unique());
        String messageId = unique();

        deadLetters.record(new IncomingDeadLetter(QUEUE, messageId, "not-json", 5, null, stillSending));

        DeadLetterEvent failed = events.findById(stillSending).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(DeadLetterStatus.REDRIVE_FAILED);
        assertThat(failed.getRedrivenAt()).isNotNull();
    }

    /** OPEN 으로 쌓고 선점(REDRIVING)까지 한 행. */
    private UUID claimed(String messageId) {
        deadLetters.record(new IncomingDeadLetter(QUEUE, messageId, "not-json", 5, null, null));
        UUID id = find(messageId).getId();
        Instant now = Instant.now();
        transactionTemplate.execute(status -> events.claimRedrive(id, "admin", now, now.minusSeconds(60)));
        return id;
    }

    /** 선점하고 보냄(REDRIVEN)까지 기록한 행. */
    private UUID redriven(String messageId) {
        UUID id = claimed(messageId);
        Instant startedAt = events.findById(id).orElseThrow().getRedriveStartedAt();
        transactionTemplate.execute(status -> events.markRedriven(id, startedAt, Instant.now()));
        return id;
    }

    private DeadLetterEvent find(String messageId) {
        UUID id = jdbcTemplate.queryForObject("SELECT id FROM payment_dead_letter_events WHERE message_id = ?",
                (rs, rowNum) -> UuidBinary.fromBytes(rs.getBytes(1)), messageId);
        return events.findById(id).orElseThrow();
    }

    private long count(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Long.class, args);
    }

    private long countWaiting(Instant staleBefore) {
        return waitingQuery.countByKind(staleBefore).stream().mapToLong(WaitingGroup::count).sum();
    }

    private static String unique() {
        return UUID.randomUUID().toString();
    }

    static String refundRequested(UUID orderId) {
        return """
                {"eventId":"%s","eventType":"ORDER_REFUND_REQUESTED","aggregateType":"ORDER","aggregateId":"%s",\
                "occurredAt":"2026-10-04T01:00:00Z","payload":{"amount":15000}}""".formatted(UUID.randomUUID(), orderId);
    }
}
