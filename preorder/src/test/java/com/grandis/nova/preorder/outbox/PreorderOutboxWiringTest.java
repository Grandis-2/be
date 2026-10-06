package com.grandis.nova.preorder.outbox;

import com.grandis.nova.common.outbox.MessageTransport;
import com.grandis.nova.common.outbox.OutboundMessage;
import com.grandis.nova.common.outbox.OutboxWriter;
import com.grandis.nova.preorder.accept.application.RegisterJobReady;
import com.grandis.nova.preorder.campaign.application.CampaignChange;
import com.grandis.nova.preorder.campaign.application.PreorderCampaignChanged;
import com.grandis.nova.preorder.cancel.application.PreorderCancelRequested;
import com.grandis.nova.preorder.event.CancelJobReady;
import com.grandis.nova.preorder.preorder.CancelReason;
import com.grandis.nova.preorder.support.PreorderIntegrationTest;
import com.grandis.nova.preorder.support.ShopFixtures;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willAnswer;

/**
 * preorder 가 내보내는 메시지의 계약 — 봉투 칸 · 종류 → 목적지 · payload 모양. order · worker · 대기열(별도 저장소)이 읽으므로
 * 바뀌면 안 된다. 실제 마이그레이션의 preorder_outbox_events 위에서, 커밋 직후 발행과 릴레이 실행기 배선까지 본다.
 *
 * 릴레이는 짧은 주기로 돌게 한다. 다른 시험이 이 주기를 물려받지 않게 끝나면 컨텍스트를 닫는다.
 */
@PreorderIntegrationTest
@TestPropertySource(properties = "nova.outbox.relay-interval=200ms")
@DirtiesContext
class PreorderOutboxWiringTest {

    static final Duration TIMEOUT = Duration.ofSeconds(10);
    static final List<String> ENVELOPE_FIELDS =
            List.of("eventId", "eventType", "aggregateType", "aggregateId", "occurredAt", "payload");

    @Autowired
    OutboxWriter writer;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    JsonMapper jsonMapper;

    @Autowired
    MeterRegistry registry;

    @MockitoBean
    MessageTransport transport;

    /** 전송 구현이 받은 메시지. */
    final Queue<OutboundMessage> sent = new ConcurrentLinkedQueue<>();

    long aggregateId;

    @BeforeEach
    void setUp() {
        aggregateId = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
        willAnswer(invocation -> sent.add(invocation.getArgument(0))).given(transport).send(any());
    }

    @Test
    void preorder_가_쓴_행은_preorder_아웃박스에만_미발행으로_UTC_시각과_함께_적힌다() {
        Instant before = Instant.now();
        Long id = transactionTemplate.execute(status -> {
            // 커밋 직후 발행이 published_at 을 채우기 전에 읽도록 같은 트랜잭션에서 본다
            Long appended = idOf(new RegisterJobReady(aggregateId, "9f1c2d3e"));
            Map<String, Object> row = jdbcTemplate.queryForMap("""
                    SELECT event_type, aggregate_type, aggregate_id, publish_attempts, published_at,
                           DATE_FORMAT(created_at, '%Y-%m-%dT%H:%i:%s.%fZ') AS created_at_utc
                      FROM preorder_outbox_events WHERE id = ?
                    """, appended);
            assertThat(row)
                    .containsEntry("event_type", "REGISTER_JOB_READY")
                    .containsEntry("aggregate_type", "PREORDER_SYNC_JOB")
                    .containsEntry("aggregate_id", aggregateId)
                    .containsEntry("publish_attempts", 0)
                    .containsEntry("published_at", null);
            assertThat(Instant.parse((String) row.get("created_at_utc"))).isBetween(before, Instant.now());
            return appended;
        });

        assertThat(id).isNotNull();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM order_outbox_events WHERE aggregate_id = ?",
                Integer.class, aggregateId)).as("order 표에는 적지 않는다").isZero();
    }

    @Test
    void 업무가_롤백되면_행도_메시지도_남지_않는다() {
        String eventId = transactionTemplate.execute(status -> {
            Long id = idOf(new RegisterJobReady(aggregateId, "9f1c2d3e"));
            status.setRollbackOnly();
            return jdbcTemplate.queryForObject(
                    "SELECT event_id FROM preorder_outbox_events WHERE id = ?", String.class, id);
        });

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM preorder_outbox_events WHERE aggregate_id = ?",
                Integer.class, aggregateId)).isZero();
        // 발행은 비동기라 바로 보면 늘 비어 있다 — 커밋 직후 발행이 끝날 만한 시간 동안 보내지 않는지 본다
        await().during(Duration.ofSeconds(1)).atMost(TIMEOUT)
                .until(() -> sent.stream().noneMatch(message -> message.eventId().equals(eventId)));
    }

    @Test
    void 커밋하면_봉투를_계약한_칸_순서로_preorder_register_에_보낸다() {
        String eventId = append(new RegisterJobReady(aggregateId, "9f1c2d3e"));

        OutboundMessage message = sentFor(eventId);
        JsonNode envelope = jsonMapper.readTree(message.body());

        assertThat(message.destination()).isEqualTo("preorder-register");
        assertThat(message.eventType()).isEqualTo("REGISTER_JOB_READY");
        assertThat(envelope.propertyNames()).containsExactlyElementsOf(ENVELOPE_FIELDS);
        assertThat(envelope.get("eventId").asString()).isEqualTo(eventId);
        assertThat(envelope.get("eventType").asString()).isEqualTo("REGISTER_JOB_READY");
        assertThat(envelope.get("aggregateType").asString()).isEqualTo("PREORDER_SYNC_JOB");
        assertThat(envelope.get("aggregateId").asLong()).isEqualTo(aggregateId);
        assertThat(envelope.get("occurredAt").isString()).as("ISO-8601 문자열").isTrue();
        assertThat(Instant.parse(envelope.get("occurredAt").asString())).as("행의 created_at(마이크로초, UTC)")
                .isEqualTo(Instant.parse(jdbcTemplate.queryForObject(
                        "SELECT DATE_FORMAT(created_at, '%Y-%m-%dT%H:%i:%s.%fZ') FROM preorder_outbox_events WHERE event_id = ?",
                        String.class, eventId)));
        assertThat(envelope.get("payload")).isEqualTo(json(
                "{\"syncJobId\":" + aggregateId + ",\"preorderId\":\"9f1c2d3e\",\"jobType\":\"REGISTER\"}"));
    }

    /** 예약 내부 id 는 봉투의 aggregateId 로만 나가고 payload 에는 싣지 않는다. */
    @Test
    void 취소_요청은_order_events_로_내부_id_없이_보낸다() {
        String eventId = append(new PreorderCancelRequested(aggregateId, "9f1c2d3e", 1024L, CancelReason.EXPIRY, 3L));

        OutboundMessage message = sentFor(eventId);
        JsonNode envelope = jsonMapper.readTree(message.body());

        assertThat(message.destination()).isEqualTo("order-events");
        assertThat(envelope.get("aggregateType").asString()).isEqualTo("PREORDER");
        assertThat(envelope.get("aggregateId").asLong()).isEqualTo(aggregateId);
        assertThat(envelope.get("payload")).isEqualTo(json(
                "{\"preorderId\":\"9f1c2d3e\",\"customerId\":1024,\"reason\":\"EXPIRY\",\"cancelSequence\":3}"));
    }

    /** 작업 종류(jobType)는 record 칸이 아니라 이벤트가 정한 고정값이다. */
    @Test
    void 취소_작업은_preorder_cancel_로_작업_종류_CANCEL_로_보낸다() {
        String eventId = append(new CancelJobReady(aggregateId, "9f1c2d3e"));

        OutboundMessage message = sentFor(eventId);

        assertThat(message.destination()).isEqualTo("preorder-cancel");
        assertThat(jsonMapper.readTree(message.body()).get("payload")).isEqualTo(json(
                "{\"syncJobId\":" + aggregateId + ",\"preorderId\":\"9f1c2d3e\",\"jobType\":\"CANCEL\"}"));
    }

    /** 대기열은 payload 의 일정을 그대로 믿는다 — 시각 형식도 계약이다. */
    @Test
    void 회차_변경은_waitingroom_events_로_일정을_ISO_시각으로_보낸다() {
        String eventId = append(new PreorderCampaignChanged(aggregateId, 4L, Instant.parse("2026-10-10T01:00:00Z"),
                Instant.parse("2026-10-11T01:00:00Z"), false, Instant.parse("2026-10-02T02:03:04.123456Z"),
                CampaignChange.RESCHEDULED));

        OutboundMessage message = sentFor(eventId);

        assertThat(message.destination()).isEqualTo("waitingroom-events");
        assertThat(jsonMapper.readTree(message.body()).get("payload")).isEqualTo(json(
                "{\"productId\":" + aggregateId + ",\"scheduleVersion\":4,\"opensAt\":\"2026-10-10T01:00:00Z\","
                        + "\"closesAt\":\"2026-10-11T01:00:00Z\",\"visible\":false,"
                        + "\"changedAt\":\"2026-10-02T02:03:04.123456Z\",\"change\":\"RESCHEDULED\"}"));
    }

    /** 커밋 직후 발행을 놓친 행(오래된 미발행)을 릴레이가 종류의 목적지로 보낸다. 종류 다섯 모두. */
    @ParameterizedTest
    @CsvSource({
            "REGISTER_JOB_READY, PREORDER_SYNC_JOB, preorder-register",
            "SYNC_JOB_REPROCESS_REQUESTED, PREORDER_SYNC_JOB, preorder-register",
            "CANCEL_JOB_READY, PREORDER_SYNC_JOB, preorder-cancel",
            "PREORDER_CANCEL_REQUESTED, PREORDER, order-events",
            "PREORDER_CAMPAIGN_CHANGED, PREORDER_CAMPAIGN, waitingroom-events"
    })
    void 릴레이는_오래된_미발행_행을_종류의_목적지로_보낸다(String eventType, String aggregateType, String destination) {
        String eventId = ShopFixtures.unique();
        jdbcTemplate.update("""
                INSERT INTO preorder_outbox_events (event_id, aggregate_type, aggregate_id, event_type, payload, created_at)
                VALUES (?, ?, ?, ?, '{}', UTC_TIMESTAMP(6) - INTERVAL 2 MINUTE)
                """, eventId, aggregateType, aggregateId, eventType);

        assertThat(sentFor(eventId).destination()).isEqualTo(destination);
        await().atMost(TIMEOUT).until(() -> jdbcTemplate.queryForObject(
                "SELECT published_at IS NOT NULL FROM preorder_outbox_events WHERE event_id = ?", Boolean.class, eventId));
    }

    /** 미발행 현황은 릴레이 주기로 센다. 지표 이름은 preorder.outbox.* 그대로다(대시보드 · 경보가 본다). */
    @Test
    void 미발행_현황_지표가_preorder_아웃박스를_센다() {
        jdbcTemplate.update("""
                INSERT INTO preorder_outbox_events (event_id, aggregate_type, aggregate_id, event_type, payload,
                                                    publish_attempts, created_at)
                VALUES (?, 'PREORDER_SYNC_JOB', ?, 'CANCEL_JOB_READY', '{}', 7, UTC_TIMESTAMP(6))
                """, ShopFixtures.unique(), aggregateId);

        await().atMost(TIMEOUT).untilAsserted(() -> {
            assertThat(registry.get("preorder.outbox.unpublished").gauge().value()).isPositive();
            assertThat(registry.get("preorder.outbox.unpublished.max.attempts").gauge().value())
                    .isGreaterThanOrEqualTo(7);
        });
    }

    private String append(OutboxMessage message) {
        Long id = transactionTemplate.execute(status -> idOf(message));
        return jdbcTemplate.queryForObject("SELECT event_id FROM preorder_outbox_events WHERE id = ?", String.class, id);
    }

    /** 적은 행의 id. 업무 트랜잭션 안에서 부른다. */
    private Long idOf(OutboxMessage message) {
        return writer.append(message);
    }

    /** payload 칸 순서는 계약이 아니다 — JSON 칸(MySQL)이 저장할 때 다시 정렬한다. 칸 이름 · 값만 본다. */
    private JsonNode json(String text) {
        return jsonMapper.readTree(text);
    }

    private OutboundMessage sentFor(String eventId) {
        return await().atMost(TIMEOUT).until(
                () -> sent.stream().filter(message -> message.eventId().equals(eventId)).findFirst().orElse(null),
                message -> message != null);
    }
}
