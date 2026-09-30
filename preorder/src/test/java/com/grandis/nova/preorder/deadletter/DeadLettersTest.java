package com.grandis.nova.preorder.deadletter;

import com.grandis.nova.common.testing.Concurrently.Outcome;
import com.grandis.nova.common.testing.Concurrently;
import com.grandis.nova.preorder.accept.AcceptResult;
import com.grandis.nova.preorder.accept.PreorderAcceptService;
import com.grandis.nova.preorder.deadletter.application.DeadLetterRedrives;
import com.grandis.nova.preorder.deadletter.domain.DeadLetterEvent;
import com.grandis.nova.preorder.deadletter.domain.DeadLetterEventRepository;
import com.grandis.nova.preorder.deadletter.domain.DeadLetterStatus;
import com.grandis.nova.preorder.deadletter.domain.FailureReason;
import com.grandis.nova.preorder.integration.catalog.CatalogClient;
import com.grandis.nova.preorder.support.AcceptFixtures;
import com.grandis.nova.preorder.support.PreorderIntegrationTest;
import com.grandis.nova.preorder.support.ShopFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** DLQ 적재와 되돌린 메시지의 결과 기록. DB 를 공유하므로 단정은 이 테스트가 만든 행으로만 한다. */
@PreorderIntegrationTest
class DeadLettersTest {

    static final String QUEUE = "preorder-events";

    @Autowired
    DeadLetters deadLetters;

    @Autowired
    DeadLetterEventRepository events;

    @Autowired
    PreorderAcceptService acceptService;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @MockitoBean
    CatalogClient catalogClient;

    ShopFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
    }

    @Test
    void 봉투를_읽어_예약과_회원을_붙여_OPEN_으로_쌓는다() {
        Long customerId = fixtures.customer();
        AcceptResult accepted = new AcceptFixtures(acceptService, fixtures, catalogClient).accept(customerId);
        String messageId = ShopFixtures.unique();
        Instant sentAt = Instant.parse("2026-09-30T01:00:00Z");

        assertThat(deadLetters.record(new IncomingDeadLetter(QUEUE, messageId,
                externalJobSucceeded(AcceptFixtures.tokenOf(accepted)), 5, sentAt, null))).isTrue();

        DeadLetterEvent event = find(messageId);
        assertThat(event.getStatus()).isEqualTo(DeadLetterStatus.OPEN);
        assertThat(event.getFailureReason()).isEqualTo(FailureReason.PROCESSING_FAILED);
        assertThat(event.getEventType()).isEqualTo("EXTERNAL_JOB_SUCCEEDED");
        assertThat(event.getPreorderId()).isEqualTo(accepted.preorder().id());
        assertThat(event.getCustomerId()).isEqualTo(customerId);
        assertThat(event.getReceiveCount()).isEqualTo(5);
        assertThat(event.getSentAt()).isEqualTo(sentAt);
    }

    @Test
    void 읽을_수_없는_원문도_그대로_쌓는다() {
        String messageId = ShopFixtures.unique();

        deadLetters.record(new IncomingDeadLetter(QUEUE, messageId, "not-json", 5, null, null));

        DeadLetterEvent event = find(messageId);
        assertThat(event.getFailureReason()).isEqualTo(FailureReason.UNREADABLE_BODY);
        assertThat(event.getBody()).isEqualTo("not-json");
        assertThat(event.getPreorderId()).isNull();
    }

    @Test
    void 지우기_전에_죽어_다시_받은_메시지는_한_번만_쌓는다() {
        String messageId = ShopFixtures.unique();
        IncomingDeadLetter incoming = new IncomingDeadLetter(QUEUE, messageId, "not-json", 5, null, null);

        assertThat(deadLetters.record(incoming)).isTrue();
        assertThat(deadLetters.record(incoming)).isFalse();

        assertThat(fixtures.count("SELECT COUNT(*) FROM dead_letter_events WHERE message_id = ?", messageId))
                .isEqualTo(1);
    }

    /** DLQ 소비기 여럿이 같은 메시지를 겹쳐 받은 경우. UNIQUE 가 한 행만 남기고, 진 쪽은 예외나 false 로 끝난다. */
    @Test
    void 같은_메시지를_동시에_적재해도_한_행만_남는다() throws Exception {
        String messageId = ShopFixtures.unique();
        IncomingDeadLetter incoming = new IncomingDeadLetter(QUEUE, messageId, "not-json", 5, null, null);

        List<Outcome<Boolean>> outcomes = Concurrently.run(4, i -> () -> deadLetters.record(incoming));

        assertThat(fixtures.count("SELECT COUNT(*) FROM dead_letter_events WHERE message_id = ?", messageId))
                .isEqualTo(1);
        assertThat(outcomes.stream().filter(outcome -> outcome.succeeded() && outcome.value())).hasSize(1);
        assertThat(outcomes).as("진 쪽은 이미 쌓임(false)이거나 UNIQUE 충돌이다 — 다른 예외는 안 된다")
                .allSatisfy(outcome -> {
                    if (!outcome.succeeded()) {
                        assertThat(outcome.error()).isInstanceOf(DataIntegrityViolationException.class)
                                .hasStackTraceContaining("uq_dead_letter_message");
                    }
                });
    }

    @Test
    void 되돌린_메시지가_또_오면_앞선_행을_REDRIVE_FAILED_로_바꾸고_새_행이_가리킨다() {
        Long previous = redriven(ShopFixtures.unique());
        String messageId = ShopFixtures.unique();

        deadLetters.record(new IncomingDeadLetter(QUEUE, messageId, "not-json", 5, null, previous));

        assertThat(find(messageId).getRedrivenFromId()).isEqualTo(previous);
        DeadLetterEvent failed = events.findById(previous).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(DeadLetterStatus.REDRIVE_FAILED);
        assertThat(failed.getOutcomeAt()).isNotNull();
    }

    @Test
    void 앞선_행이_없는_속성이면_잇지_않고_쌓는다() {
        String messageId = ShopFixtures.unique();

        deadLetters.record(new IncomingDeadLetter(QUEUE, messageId, "not-json", 5, null, Long.MAX_VALUE));

        assertThat(find(messageId).getRedrivenFromId()).isNull();
    }

    @Test
    void 되돌린_메시지가_처리되면_SUCCEEDED_이고_보냄_기록보다_먼저여도_남는다() {
        Long redriven = redriven(ShopFixtures.unique());
        Long stillSending = claimed(ShopFixtures.unique());

        deadLetters.markRedriveSucceeded(redriven);
        deadLetters.markRedriveSucceeded(stillSending);

        assertThat(events.findById(redriven).orElseThrow().getStatus()).isEqualTo(DeadLetterStatus.SUCCEEDED);
        DeadLetterEvent early = events.findById(stillSending).orElseThrow();
        assertThat(early.getStatus()).isEqualTo(DeadLetterStatus.SUCCEEDED);
        assertThat(early.getRedrivenAt()).as("결과 시각으로 채운다").isNotNull();
    }

    @Test
    void 되돌리지_않은_행에는_처리_결과를_남기지_않는다() {
        String messageId = ShopFixtures.unique();
        deadLetters.record(new IncomingDeadLetter(QUEUE, messageId, "not-json", 5, null, null));
        Long id = find(messageId).getId();

        deadLetters.markRedriveSucceeded(id);

        assertThat(events.findById(id).orElseThrow().getStatus()).isEqualTo(DeadLetterStatus.OPEN);
    }

    @Test
    void 보내다_멈춘_REDRIVING_은_1분이_지나야_되돌리기_대기로_센다() {
        Long recent = claimed(ShopFixtures.unique());
        Long stale = claimed(ShopFixtures.unique());
        jdbcTemplate.update("UPDATE dead_letter_events SET redrive_started_at = redrive_started_at - INTERVAL 2 MINUTE"
                + " WHERE id = ?", stale);
        Instant staleBefore = Instant.now().minus(DeadLetterRedrives.STALE_REDRIVE);

        List<Long> waiting = events.findWaitingIds(null, FailureReason.UNREADABLE_BODY, staleBefore, Integer.MAX_VALUE);

        assertThat(waiting).contains(stale).doesNotContain(recent);
        assertThat(events.findById(stale).orElseThrow().waitingForRedrive(staleBefore)).isTrue();
        assertThat(events.findById(recent).orElseThrow().waitingForRedrive(staleBefore)).isFalse();
        long before = events.countWaiting(staleBefore);
        jdbcTemplate.update("UPDATE dead_letter_events SET redrive_started_at = redrive_started_at - INTERVAL 2 MINUTE"
                + " WHERE id = ?", recent);
        assertThat(events.countWaiting(staleBefore)).isEqualTo(before + 1);
    }

    @Test
    void 보냄_기록_전에_또_DLQ_로_와도_앞선_행은_REDRIVE_FAILED_다() {
        Long stillSending = claimed(ShopFixtures.unique());
        String messageId = ShopFixtures.unique();

        deadLetters.record(new IncomingDeadLetter(QUEUE, messageId, "not-json", 5, null, stillSending));

        DeadLetterEvent failed = events.findById(stillSending).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(DeadLetterStatus.REDRIVE_FAILED);
        assertThat(failed.getRedrivenAt()).isNotNull();
    }

    /** OPEN 으로 쌓고 선점(REDRIVING)까지 한 행. */
    private Long claimed(String messageId) {
        deadLetters.record(new IncomingDeadLetter(QUEUE, messageId, "not-json", 5, null, null));
        Long id = find(messageId).getId();
        Instant now = Instant.now();
        transactionTemplate.execute(status -> events.claimRedrive(id, "admin", now, now.minusSeconds(60)));
        return id;
    }

    /** 선점하고 보냄(REDRIVEN)까지 기록한 행. */
    private Long redriven(String messageId) {
        Long id = claimed(messageId);
        Instant startedAt = events.findById(id).orElseThrow().getRedriveStartedAt();
        transactionTemplate.execute(status -> events.markRedriven(id, startedAt, Instant.now()));
        return id;
    }

    private DeadLetterEvent find(String messageId) {
        Long id = jdbcTemplate.queryForObject("SELECT id FROM dead_letter_events WHERE message_id = ?", Long.class,
                messageId);
        return events.findById(id).orElseThrow();
    }

    private String externalJobSucceeded(String token) {
        return """
                {"eventId":"%s","eventType":"EXTERNAL_JOB_SUCCEEDED","aggregateType":"PREORDER_SYNC_JOB",
                 "aggregateId":1,"occurredAt":"2026-09-03T01:00:03.470Z",
                 "payload":{"syncJobId":1,"preorderId":"%s","jobType":"REGISTER","externalNumber":"R-1"}}
                """.formatted(ShopFixtures.unique(), token);
    }
}
