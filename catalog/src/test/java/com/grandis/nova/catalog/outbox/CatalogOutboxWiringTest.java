package com.grandis.nova.catalog.outbox;

import com.grandis.nova.catalog.registration.InStockProductRegistered;
import com.grandis.nova.catalog.registration.PreorderProductRegistered;
import com.grandis.nova.catalog.support.CatalogIntegrationTest;
import com.grandis.nova.common.outbox.MessageTransport;
import com.grandis.nova.common.outbox.OutboundMessage;
import com.grandis.nova.common.outbox.OutboxWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willAnswer;

/**
 * catalog 가 공통 아웃박스(common:outbox)에 제대로 엮였는가 — 실제 마이그레이션의 catalog_outbox_events 위에서 본다.
 * 기록 · 발행 · 릴레이 동작 자체는 common:outbox 시험이 본다. 여기서는 catalog 의 표 · 종류 · 목적지 · payload 계약을 본다.
 *
 * payload 기준 문자열은 공통 모듈로 옮기기 전 catalog 의 전용 매퍼(JsonMapper.builder().build())가 낸 값이다(실측).
 * 옮긴 뒤에는 앱의 JsonMapper 빈이 쓴다 — 받는 쪽 계약이라 글자까지 같아야 한다.
 *
 * 릴레이는 전용 실행기가 짧은 주기로 돌게 한다(배선까지 본다). 다른 시험이 이 주기를 물려받지 않게 끝나면 컨텍스트를 닫는다.
 */
@CatalogIntegrationTest
@TestPropertySource(properties = "nova.outbox.relay-interval=200ms")
@DirtiesContext
class CatalogOutboxWiringTest {

    static final Duration TIMEOUT = Duration.ofSeconds(10);
    static final String PREORDER_PAYLOAD = "{\"campaign\":{\"opensAt\":\"2026-10-10T03:00:00.123456Z\",\"closesAt\":\"2026-10-13T03:00:00Z\"},"
            + "\"shipmentBatches\":[{\"batchNumber\":1,\"positionFrom\":1,\"positionTo\":100,\"estimatedShipStart\":\"2026-11-01\",\"estimatedShipEnd\":\"2026-11-07\"},"
            + "{\"batchNumber\":2,\"positionFrom\":101,\"positionTo\":null,\"estimatedShipStart\":\"2026-11-08\",\"estimatedShipEnd\":\"2026-11-14\"}]}";
    static final String IN_STOCK_PAYLOAD = "{\"items\":[{\"optionId\":101,\"stockTotal\":5},{\"optionId\":102,\"stockTotal\":0}]}";
    /** 상품 표와 잇지 않는 표라 상품 행 없이 대상 id 만 겹치지 않게 고른다. */
    static final AtomicLong NEXT_PRODUCT = new AtomicLong(System.currentTimeMillis());

    @Autowired OutboxWriter writer;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired JsonMapper jsonMapper;

    @MockitoBean MessageTransport transport;

    /** 전송 구현이 받은 메시지. */
    final Queue<OutboundMessage> sent = new ConcurrentLinkedQueue<>();

    @BeforeEach
    void setUp() {
        willAnswer(invocation -> sent.add(invocation.getArgument(0))).given(transport).send(any());
    }

    @Test
    @DisplayName("앱의 JsonMapper 가 쓰는 payload 는 공통 모듈로 옮기기 전 전용 매퍼의 값과 글자까지 같다")
    void payloadTextIsUnchanged() {
        assertThat(jsonMapper.writeValueAsString(preorder(1L))).isEqualTo(PREORDER_PAYLOAD);
        assertThat(jsonMapper.writeValueAsString(inStock(1L))).isEqualTo(IN_STOCK_PAYLOAD);
    }

    @Test
    @DisplayName("catalog 메시지는 catalog_outbox_events 에 종류 · 대상 · payload 그대로 적히고 다른 서비스 표에는 적히지 않는다")
    void writesToCatalogTable() {
        Long productId = NEXT_PRODUCT.incrementAndGet();
        Long id = transactionTemplate.execute(status -> writer.append(preorder(productId)));

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT event_id, event_type, aggregate_type, aggregate_id, payload FROM catalog_outbox_events WHERE id = ?", id);
        assertThat(row)
                .containsEntry("event_type", "PREORDER_PRODUCT_REGISTERED")
                .containsEntry("aggregate_type", "PRODUCT")
                .containsEntry("aggregate_id", productId);
        assertThat(jsonMapper.readTree((String) row.get("payload"))).isEqualTo(jsonMapper.readTree(PREORDER_PAYLOAD));
        for (String other : List.of("preorder_outbox_events", "order_outbox_events")) {
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + other + " WHERE event_id = ?", Integer.class, row.get("event_id")))
                    .as(other + " 에는 적지 않는다").isZero();
        }
    }

    @Test
    @DisplayName("커밋 직후 종류마다 정한 목적지로 봉투를 보낸다 — 사전예약은 preorder-events, 일반은 order-events")
    void publishesToEachDestination() {
        Long preorderProduct = NEXT_PRODUCT.incrementAndGet();
        Long inStockProduct = NEXT_PRODUCT.incrementAndGet();
        String preorderEvent = eventIdOf(transactionTemplate.execute(status -> writer.append(preorder(preorderProduct))));
        String inStockEvent = eventIdOf(transactionTemplate.execute(status -> writer.append(inStock(inStockProduct))));

        await().atMost(TIMEOUT).until(() -> sentOf(preorderEvent).isPresent() && sentOf(inStockEvent).isPresent());

        OutboundMessage toPreorder = sentOf(preorderEvent).orElseThrow();
        assertThat(toPreorder.destination()).isEqualTo("preorder-events");
        assertThat(toPreorder.eventType()).isEqualTo("PREORDER_PRODUCT_REGISTERED");
        JsonNode preorderEnvelope = jsonMapper.readTree(toPreorder.body());
        assertThat(preorderEnvelope.get("aggregateType").asString()).isEqualTo("PRODUCT");
        assertThat(preorderEnvelope.get("aggregateId").asLong()).isEqualTo(preorderProduct);
        assertThat(preorderEnvelope.get("payload")).isEqualTo(jsonMapper.readTree(PREORDER_PAYLOAD));

        OutboundMessage toOrder = sentOf(inStockEvent).orElseThrow();
        assertThat(toOrder.destination()).isEqualTo("order-events");
        assertThat(toOrder.eventType()).isEqualTo("IN_STOCK_PRODUCT_REGISTERED");
        assertThat(jsonMapper.readTree(toOrder.body()).get("payload")).isEqualTo(jsonMapper.readTree(IN_STOCK_PAYLOAD));

        await().atMost(TIMEOUT).until(() -> jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM catalog_outbox_events WHERE event_id IN (?, ?) AND published_at IS NOT NULL",
                Integer.class, preorderEvent, inStockEvent) == 2);
    }

    /**
     * 공통 모듈의 실패 · 재전송 시험은 시험용 표에서 돈다. catalog 표는 그 표와 칸 · 형 · 인덱스가 같고
     * 실패 횟수 CHECK(publish_attempts >= 0)만 더 있다 — 실패 기록(UPDATE)이 그 제약을 지나 다시 보내지는지 catalog 표에서 본다.
     * 커밋 직후 발행을 놓친 행(만든 지 2분)이라 릴레이가 집는다.
     */
    @Test
    @DisplayName("전송이 실패하면 catalog 표의 그 행은 실패 횟수가 오른 채 미발행으로 남고, 릴레이가 다시 보내 발행 완료가 된다")
    void failedSendIsRetriedFromCatalogTable() {
        String eventId = UUID.randomUUID().toString();
        AtomicInteger calls = new AtomicInteger();
        willAnswer(invocation -> {
            OutboundMessage message = invocation.getArgument(0);
            if (message.eventId().equals(eventId) && calls.incrementAndGet() == 1) {
                throw new IllegalStateException("첫 전송 실패(큐 장애 흉내)");
            }
            sent.add(message);
            return null;
        }).given(transport).send(any());
        jdbcTemplate.update("""
                INSERT INTO catalog_outbox_events (event_id, aggregate_type, aggregate_id, event_type, payload, created_at)
                VALUES (?, 'PRODUCT', ?, 'IN_STOCK_PRODUCT_REGISTERED', ?, UTC_TIMESTAMP(6) - INTERVAL 2 MINUTE)
                """, eventId, NEXT_PRODUCT.incrementAndGet(), IN_STOCK_PAYLOAD);

        await().atMost(TIMEOUT).until(() -> sentOf(eventId).isPresent());
        await().atMost(TIMEOUT).until(() -> jdbcTemplate.queryForObject(
                "SELECT published_at IS NOT NULL FROM catalog_outbox_events WHERE event_id = ?", Boolean.class, eventId));

        assertThat(calls).as("한 번 실패하고 한 번 더 보냈다").hasValue(2);
        assertThat(sentOf(eventId).orElseThrow().destination()).isEqualTo("order-events");
        assertThat(jdbcTemplate.queryForObject("SELECT publish_attempts FROM catalog_outbox_events WHERE event_id = ?",
                Integer.class, eventId)).as("실패 한 번이 표에 남는다").isEqualTo(1);
    }

    private static PreorderProductRegistered preorder(Long productId) {
        return new PreorderProductRegistered(productId,
                new PreorderProductRegistered.Campaign(Instant.parse("2026-10-10T03:00:00.123456Z"), Instant.parse("2026-10-13T03:00:00Z")),
                List.of(new PreorderProductRegistered.ShipmentBatch(1, 1, 100L, LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-07")),
                        new PreorderProductRegistered.ShipmentBatch(2, 101, null, LocalDate.parse("2026-11-08"), LocalDate.parse("2026-11-14"))));
    }

    private static InStockProductRegistered inStock(Long productId) {
        return new InStockProductRegistered(productId,
                List.of(new InStockProductRegistered.Item(101L, 5), new InStockProductRegistered.Item(102L, 0)));
    }

    private String eventIdOf(Long rowId) {
        return jdbcTemplate.queryForObject("SELECT event_id FROM catalog_outbox_events WHERE id = ?", String.class, rowId);
    }

    private Optional<OutboundMessage> sentOf(String eventId) {
        return sent.stream().filter(message -> message.eventId().equals(eventId)).findFirst();
    }
}
