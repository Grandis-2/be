package com.grandis.nova.order.outbox;

import com.grandis.nova.common.outbox.MessageTransport;
import com.grandis.nova.common.outbox.OutboundMessage;
import com.grandis.nova.common.outbox.OutboxWriter;
import com.grandis.nova.order.order.OrderLedger;
import com.grandis.nova.order.order.domain.enums.OrderStatus;
import com.grandis.nova.order.order.domain.enums.OrderTrigger;
import com.grandis.nova.order.order.vo.EventCause;
import com.grandis.nova.order.outbox.PreorderOrderSettled.RejectReason;
import com.grandis.nova.order.support.OrderFixtures;
import com.grandis.nova.order.support.OrderFixtures.PreorderProduct;
import com.grandis.nova.order.support.OrderIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

import static com.grandis.nova.order.support.OrderFixtures.preorderCommand;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willAnswer;

/**
 * order 가 공통 아웃박스(common:outbox)에 제대로 엮였는가 — 실제 마이그레이션의 order_outbox_events 위에서 본다.
 * 기록 · 발행 · 릴레이 동작 자체는 common:outbox 시험이 본다. 여기서는 order 의 표 · 종류 · 목적지 · payload 계약과
 * 주문 원장과의 트랜잭션 경계를 본다.
 *
 * 릴레이는 전용 실행기가 짧은 주기로 돌게 한다(배선까지 본다). 다른 시험이 이 주기를 물려받지 않게 끝나면 컨텍스트를 닫는다.
 */
@OrderIntegrationTest
@TestPropertySource(properties = "nova.outbox.relay-interval=200ms")
@DirtiesContext
class OrderOutboxWiringTest {

    static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Autowired
    OutboxWriter writer;

    @Autowired
    OrderLedger ledger;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    JsonMapper jsonMapper;

    @Autowired
    Clock clock;

    @MockitoBean
    MessageTransport transport;

    /** 전송 구현이 받은 메시지. */
    final Queue<OutboundMessage> sent = new ConcurrentLinkedQueue<>();

    OrderFixtures fixtures;
    PreorderProduct product;
    Long customerId;
    final AtomicLong nextPosition = new AtomicLong(1);

    @BeforeEach
    void setUp() {
        fixtures = new OrderFixtures(jdbcTemplate);
        product = fixtures.preorderProduct();
        customerId = fixtures.customer();
        willAnswer(invocation -> sent.add(invocation.getArgument(0))).given(transport).send(any());
    }

    @Test
    void order_가_쓴_행은_order_아웃박스에_미발행으로_UTC_시각과_함께_적힌다() {
        Long preorderId = preorder();
        Instant before = clock.instant();
        Long id = transactionTemplate.execute(status ->
                writer.append(PreorderOrderSettled.canceled(preorderId, tokenOf(preorderId), 3L)));
        Instant after = clock.instant();

        Map<String, Object> row = jdbcTemplate.queryForMap("""
                SELECT event_type, aggregate_type, aggregate_id,
                       DATE_FORMAT(created_at, '%Y-%m-%dT%H:%i:%s.%fZ') AS created_at_utc
                  FROM order_outbox_events WHERE id = ?
                """, id);

        assertThat(row)
                .containsEntry("event_type", "PREORDER_ORDER_SETTLED")
                .containsEntry("aggregate_type", "PREORDER")
                .containsEntry("aggregate_id", preorderId);
        assertThat(Instant.parse((String) row.get("created_at_utc"))).isBetween(before, after);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM preorder_outbox_events WHERE aggregate_id = ?",
                Integer.class, preorderId)).as("preorder 표에는 적지 않는다").isZero();
    }

    /** 커밋 직후 발행이 order 의 종류 → 목적지(preorder-events)로 봉투를 보낸다. */
    @Test
    void 커밋하면_preorder_events_로_정리_결과_봉투를_보낸다() {
        Long preorderId = preorder();
        String token = tokenOf(preorderId);
        Long id = transactionTemplate.execute(status ->
                writer.append(PreorderOrderSettled.rejected(preorderId, token, RejectReason.SHIPPED, 7L)));
        String eventId = eventIdOf(id);

        await().atMost(TIMEOUT).until(() -> sentOf(eventId).isPresent());

        OutboundMessage message = sentOf(eventId).orElseThrow();
        assertThat(message.destination()).isEqualTo("preorder-events");
        JsonNode body = jsonMapper.readTree(message.body());
        assertThat(body.get("eventType").asString()).isEqualTo("PREORDER_ORDER_SETTLED");
        assertThat(body.get("aggregateType").asString()).isEqualTo("PREORDER");
        assertThat(body.get("aggregateId").asLong()).isEqualTo(preorderId);
        assertThat(body.get("payload").get("preorderId").asString()).isEqualTo(token);
    }

    /** 커밋 직후 발행을 놓친 행(만든 지 2분)을 order 의 릴레이가 전용 실행기에서 집어 보낸다. */
    @Test
    void 릴레이가_order_아웃박스의_오래된_미발행_행을_집어_보낸다() {
        String eventId = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO order_outbox_events (event_id, aggregate_type, aggregate_id, event_type, payload, created_at)
                VALUES (?, 'PREORDER', ?, 'PREORDER_ORDER_SETTLED', '{}', UTC_TIMESTAMP(6) - INTERVAL 2 MINUTE)
                """, eventId, ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE));

        await().atMost(TIMEOUT).until(() -> sentOf(eventId).isPresent());

        assertThat(sentOf(eventId).orElseThrow().destination()).isEqualTo("preorder-events");
        await().atMost(TIMEOUT).until(() -> jdbcTemplate.queryForObject(
                "SELECT published_at IS NOT NULL FROM order_outbox_events WHERE event_id = ?", Boolean.class, eventId));
    }

    /**
     * 저장된 payload 를 preorder 의 받는 쪽 record(복제)로 풀어 본다. 칸 이름 · enum 이름 · 필수 칸이 어긋나면 여기서 깨진다.
     * 받는 쪽 JsonMapper 는 모르는 칸을 무시할 수 있으므로 칸 목록도 정확히 대조한다.
     */
    @Test
    void payload_는_preorder_가_받는_계약과_같다() {
        Long rejectedPreorder = preorder();
        String rejectedToken = tokenOf(rejectedPreorder);
        // 한 회원은 한 상품에 활성 예약 하나(uq_preorder_active)라 두 번째 예약은 다른 회원으로 만든다.
        Long noOrderPreorder = fixtures.payablePreorder(fixtures.customer(), product, nextPosition.getAndIncrement());
        String noOrderToken = tokenOf(noOrderPreorder);

        List<Long> ids = transactionTemplate.execute(status -> List.of(
                writer.append(PreorderOrderSettled.rejected(rejectedPreorder, rejectedToken, RejectReason.SHIPPED, 7L)),
                writer.append(PreorderOrderSettled.noOrder(noOrderPreorder, noOrderToken, 2L))));

        assertThat(jsonMapper.readTree(payload(ids.get(0))).propertyNames())
                .containsExactlyInAnyOrder("preorderId", "result", "reason", "cancelSequence");
        assertThat(receivedByPreorder(ids.get(0))).isEqualTo(new PreorderSideSettled(
                rejectedToken, PreorderSideSettled.Result.REJECTED, "SHIPPED", 7L));
        assertThat(receivedByPreorder(ids.get(1))).isEqualTo(new PreorderSideSettled(
                noOrderToken, PreorderSideSettled.Result.NO_ORDER, null, 2L));
    }

    /** 주문 전이 · 이력 · 아웃박스가 한 트랜잭션. 그 뒤 실패하면 셋 다 남지 않는다. */
    @Test
    void 업무가_실패하면_전이_이력_아웃박스가_모두_남지_않는다() {
        Long preorderId = preorder();
        String token = tokenOf(preorderId);
        Long orderId = placeOrder(preorderId);

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            ledger.fire(orderId, OrderTrigger.CANCEL_REQUESTED, EnumSet.of(OrderStatus.AWAITING_PAYMENT),
                    EventCause.system("USER"));
            writer.append(PreorderOrderSettled.canceled(preorderId, token, 5L));
            throw new IllegalStateException("업무 실패");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(orderStatus(orderId)).isEqualTo("AWAITING_PAYMENT");
        assertThat(eventsOf(orderId)).isEqualTo(1);
        assertThat(outboxRowsOf(preorderId)).isZero();
    }

    /** 아웃박스를 먼저 적고 전이해도 함께 커밋된다 — 원장이 영속성 컨텍스트를 다뤄도 JDBC 로 적은 행과는 상관없다. */
    @Test
    void 전이와_아웃박스는_함께_커밋된다() {
        Long preorderId = preorder();
        String token = tokenOf(preorderId);
        Long orderId = placeOrder(preorderId);

        transactionTemplate.executeWithoutResult(status -> {
            writer.append(PreorderOrderSettled.canceled(preorderId, token, 5L));
            ledger.fire(orderId, OrderTrigger.CANCEL_REQUESTED, EnumSet.of(OrderStatus.AWAITING_PAYMENT),
                    EventCause.system("USER"));
        });

        assertThat(orderStatus(orderId)).isEqualTo("CANCELED");
        assertThat(eventsOf(orderId)).isEqualTo(2);
        assertThat(outboxRowsOf(preorderId)).isOne();
    }

    private Long preorder() {
        return fixtures.payablePreorder(customerId, product, nextPosition.getAndIncrement());
    }

    private Long placeOrder(Long preorderId) {
        return transactionTemplate.execute(status -> ledger.place(
                preorderCommand(customerId, preorderId, product).toDraft(), EventCause.user()).id());
    }

    private String tokenOf(Long preorderId) {
        return jdbcTemplate.queryForObject("SELECT preorder_token FROM preorders WHERE id = ?", String.class, preorderId);
    }

    private String eventIdOf(Long id) {
        return jdbcTemplate.queryForObject("SELECT event_id FROM order_outbox_events WHERE id = ?", String.class, id);
    }

    private String orderStatus(Long orderId) {
        return jdbcTemplate.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId);
    }

    private int eventsOf(Long orderId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM order_events WHERE order_id = ?", Integer.class, orderId);
    }

    private int outboxRowsOf(Long preorderId) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM order_outbox_events WHERE aggregate_type = 'PREORDER' AND aggregate_id = ?
                """, Integer.class, preorderId);
    }

    private String payload(Long id) {
        return jdbcTemplate.queryForObject("SELECT payload FROM order_outbox_events WHERE id = ?", String.class, id);
    }

    private PreorderSideSettled receivedByPreorder(Long id) {
        return jsonMapper.readValue(payload(id), PreorderSideSettled.class);
    }

    private Optional<OutboundMessage> sentOf(String eventId) {
        return sent.stream().filter(message -> message.eventId().equals(eventId)).findFirst();
    }

    /**
     * preorder {@code event.PreorderOrderSettled} 를 그대로 옮긴 것(모듈 간 의존 금지라 참조할 수 없다).
     * 원본이 바뀌면 이것도 맞춘다. 계약을 common:message 로 옮기면 지운다.
     */
    record PreorderSideSettled(String preorderId, Result result, String reason, Long cancelSequence) {

        PreorderSideSettled {
            if (cancelSequence == null) {
                throw new IllegalArgumentException("cancelSequence 가 없다: preorderId=" + preorderId);
            }
        }

        enum Result {
            NO_ORDER,
            CANCELED,
            REJECTED
        }
    }
}
