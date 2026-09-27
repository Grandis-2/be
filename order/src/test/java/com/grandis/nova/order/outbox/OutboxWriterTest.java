package com.grandis.nova.order.outbox;

import com.grandis.nova.order.order.OrderLedger;
import com.grandis.nova.order.order.domain.enums.OrderStatus;
import com.grandis.nova.order.order.domain.enums.OrderTrigger;
import com.grandis.nova.order.order.vo.EventCause;
import com.grandis.nova.order.outbox.PreorderOrderSettled.RejectReason;
import com.grandis.nova.order.support.OrderFixtures;
import com.grandis.nova.order.support.OrderFixtures.PreorderProduct;
import com.grandis.nova.order.support.OrderIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static com.grandis.nova.order.support.OrderFixtures.preorderCommand;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 아웃박스 기록과 커밋 후 신호. 커밋 · 롤백을 직접 보려고 테스트 트랜잭션 대신 TransactionTemplate 을 쓴다.
 * 행은 테스트마다 새 예약(내부 id · 토큰)으로 적어 서로 겹치지 않는다.
 */
@OrderIntegrationTest
@Import(OutboxWriterTest.CommittedEvents.class)
class OutboxWriterTest {

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
    CommittedEvents committedEvents;

    @Autowired
    Clock clock;

    @PersistenceContext
    EntityManager entityManager;

    OrderFixtures fixtures;
    PreorderProduct product;
    Long customerId;
    final AtomicLong nextPosition = new AtomicLong(1);

    @BeforeEach
    void setUp() {
        fixtures = new OrderFixtures(jdbcTemplate);
        product = fixtures.preorderProduct();
        customerId = fixtures.customer();
        committedEvents.received.clear();
    }

    @Test
    void appendWritesRowUnpublished() {
        Long preorderId = preorder();
        String token = tokenOf(preorderId);
        Instant before = clock.instant();
        Long id = transactionTemplate.execute(status ->
                writer.append(PreorderOrderSettled.canceled(preorderId, token, 3L)));
        Instant after = clock.instant();

        Map<String, Object> row = jdbcTemplate.queryForMap("""
                SELECT event_id, event_type, aggregate_type, aggregate_id, publish_attempts, published_at,
                       DATE_FORMAT(created_at, '%Y-%m-%dT%H:%i:%s.%fZ') AS created_at_utc
                  FROM outbox_events WHERE id = ?
                """, id);

        assertThat(row)
                .containsEntry("event_type", "PREORDER_ORDER_SETTLED")
                .containsEntry("aggregate_type", "PREORDER")
                .containsEntry("aggregate_id", preorderId)
                .containsEntry("publish_attempts", 0)
                .containsEntry("published_at", null);
        // 감사(Auditing)가 원장과 같은 UTC 시계로 채운다. 감사가 빠지거나 시간대가 어긋나면(KST 로컬 시각) 구간을 벗어난다.
        assertThat(Instant.parse((String) row.get("created_at_utc"))).isBetween(before, after);
        assertThat((String) row.get("event_id")).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    /**
     * 저장된 payload 를 preorder 의 받는 쪽 record(복제)로 풀어 본다. 칸 이름 · enum 이름 · 필수 칸이 어긋나면 여기서 깨진다.
     * 받는 쪽 JsonMapper 는 모르는 칸을 무시할 수 있으므로 칸 목록도 정확히 대조한다.
     */
    @Test
    void payloadMatchesPreorderContract() {
        Long rejectedPreorder = preorder();
        String rejectedToken = tokenOf(rejectedPreorder);
        // 한 회원은 한 상품에 활성 예약 하나(uq_preorder_active)라 두 번째 예약은 다른 회원으로 만든다.
        Long noOrderPreorder = fixtures.payablePreorder(fixtures.customer(), product, nextPosition.getAndIncrement());
        String noOrderToken = tokenOf(noOrderPreorder);

        List<Long> ids = transactionTemplate.execute(status -> List.of(
                writer.append(PreorderOrderSettled.rejected(rejectedPreorder, rejectedToken, RejectReason.SHIPPED, 7L)),
                writer.append(PreorderOrderSettled.noOrder(noOrderPreorder, noOrderToken, 2L))));

        assertThat(payloadKeys(ids.get(0))).containsExactlyInAnyOrder("preorderId", "result", "reason", "cancelSequence");
        assertThat(receivedByPreorder(ids.get(0))).isEqualTo(new PreorderSideSettled(
                rejectedToken, PreorderSideSettled.Result.REJECTED, "SHIPPED", 7L));
        assertThat(receivedByPreorder(ids.get(1))).isEqualTo(new PreorderSideSettled(
                noOrderToken, PreorderSideSettled.Result.NO_ORDER, null, 2L));
    }

    @Test
    void commitSignalsAppendedOnce() {
        Long preorderId = preorder();
        Long id = transactionTemplate.execute(status ->
                writer.append(PreorderOrderSettled.noOrder(preorderId, tokenOf(preorderId), 1L)));

        assertThat(committedEvents.received).containsExactly(new OutboxAppended(id));
    }

    @Test
    void rollbackLeavesNoRowAndNoSignal() {
        Long preorderId = preorder();
        String token = tokenOf(preorderId);
        transactionTemplate.executeWithoutResult(status -> {
            writer.append(PreorderOrderSettled.noOrder(preorderId, token, 1L));
            status.setRollbackOnly();
        });

        assertThat(outboxRowsOf(preorderId)).isZero();
        assertThat(committedEvents.received).isEmpty();
    }

    /** F6 의 모양: 주문 전이 · 이력 · 아웃박스가 한 트랜잭션. 그 뒤 실패하면 셋 다 남지 않는다. */
    @Test
    void businessRollbackDiscardsTransitionAndOutbox() {
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
        assertThat(committedEvents.received).isEmpty();
    }

    /**
     * 아웃박스를 먼저 적고 전이한다. 원장은 상태를 바꾼 뒤 그 주문 엔티티만 떼어내므로(컨텍스트 전체를 비우지 않는다)
     * 앞서 적은 아웃박스 엔티티는 관리 상태로 남고, 전이와 함께 커밋된다.
     */
    @Test
    void transitionAndOutboxCommitTogether() {
        Long preorderId = preorder();
        String token = tokenOf(preorderId);
        Long orderId = placeOrder(preorderId);

        Long id = transactionTemplate.execute(status -> {
            Long appended = writer.append(PreorderOrderSettled.canceled(preorderId, token, 5L));
            OutboxEvent managed = entityManager.find(OutboxEvent.class, appended);
            ledger.fire(orderId, OrderTrigger.CANCEL_REQUESTED, EnumSet.of(OrderStatus.AWAITING_PAYMENT),
                    EventCause.system("USER"));
            // IDENTITY 라 행은 이미 INSERT 됐다 — 커밋 여부만으로는 컨텍스트를 비웠는지 알 수 없어 관리 상태를 직접 본다.
            assertThat(entityManager.contains(managed)).isTrue();
            return appended;
        });

        assertThat(orderStatus(orderId)).isEqualTo("CANCELED");
        assertThat(eventsOf(orderId)).isEqualTo(2);
        assertThat(outboxRowsOf(preorderId)).isOne();
        assertThat(committedEvents.received).containsExactly(new OutboxAppended(id));
    }

    @Test
    void requiresTransaction() {
        Long preorderId = preorder();
        String token = tokenOf(preorderId);

        assertThatThrownBy(() -> writer.append(PreorderOrderSettled.noOrder(preorderId, token, 1L)))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(outboxRowsOf(preorderId)).isZero();
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

    private String orderStatus(Long orderId) {
        return jdbcTemplate.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId);
    }

    private int eventsOf(Long orderId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM order_events WHERE order_id = ?", Integer.class, orderId);
    }

    private int outboxRowsOf(Long preorderId) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM outbox_events WHERE aggregate_type = 'PREORDER' AND aggregate_id = ?
                """, Integer.class, preorderId);
    }

    private String payload(Long id) {
        return jdbcTemplate.queryForObject("SELECT payload FROM outbox_events WHERE id = ?", String.class, id);
    }

    private List<String> payloadKeys(Long id) {
        JsonNode node = jsonMapper.readTree(payload(id));
        return node.propertyNames().stream().toList();
    }

    private PreorderSideSettled receivedByPreorder(Long id) {
        return jsonMapper.readValue(payload(id), PreorderSideSettled.class);
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

    /** 발행기 자리. 커밋 뒤에 받은 신호를 모은다. */
    @TestConfiguration(proxyBeanMethods = false)
    static class CommittedEvents {

        final List<OutboxAppended> received = new CopyOnWriteArrayList<>();

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        void on(OutboxAppended event) {
            received.add(event);
        }
    }
}
