package com.grandis.nova.order.order.cancel;

import com.grandis.nova.order.order.OrderLedger;
import com.grandis.nova.order.order.domain.model.Order;
import com.grandis.nova.order.outbox.PreorderOrderSettled.RejectReason;
import com.grandis.nova.order.outbox.PreorderOrderSettled.Result;
import com.grandis.nova.common.testing.Concurrently;
import com.grandis.nova.common.testing.Concurrently.Outcome;
import com.grandis.nova.order.support.OrderFixtures;
import com.grandis.nova.order.support.OrderIntegrationTest;
import com.grandis.nova.order.support.PlacedOrders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 예약 취소 수신의 주문 정리. 유스케이스가 트랜잭션을 열고 커밋하므로 결과는 DB 에서 직접 읽는다.
 * 테스트마다 새 예약(내부 id)을 써서 아웃박스 행이 겹치지 않는다.
 */
@OrderIntegrationTest
class SettlePreorderCancelServiceTest {

    static final long CANCEL_SEQUENCE = 3L;
    static final int REQUESTS = 5;

    @Autowired
    SettlePreorderCancelService settlement;

    @Autowired
    OrderLedger ledger;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    JsonMapper jsonMapper;

    OrderFixtures fixtures;
    PlacedOrders placedOrders;
    Long customerId;

    @BeforeEach
    void setUp() {
        fixtures = new OrderFixtures(jdbcTemplate);
        placedOrders = new PlacedOrders(ledger, transactionTemplate, fixtures);
        customerId = fixtures.customer();
    }

    @Test
    void 주문이_없으면_NO_ORDER_를_받은_시도_순번과_함께_적는다() {
        Long preorderId = fixtures.payablePreorder(customerId, fixtures.preorderProduct(), 1);
        String preorderUuid = uuidOf(preorderId);

        settlement.settle(cancel(preorderId, preorderUuid, CancelReason.USER));

        List<Map<String, Object>> rows = outboxRows(preorderId);
        assertThat(rows).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("event_type", "PREORDER_ORDER_SETTLED")
                .containsEntry("aggregate_type", "PREORDER")
                .containsEntry("aggregate_id", preorderId));
        JsonNode payload = payloadOf(rows.getFirst());
        assertThat(payload.propertyNames()).containsExactlyInAnyOrder("preorderId", "result", "reason", "cancelSequence");
        assertThat(payload.get("preorderId").asString()).isEqualTo(preorderUuid);
        assertThat(payload.get("result").asString()).isEqualTo("NO_ORDER");
        assertThat(payload.get("reason").isNull()).isTrue();
        assertThat(payload.get("cancelSequence").asLong()).isEqualTo(CANCEL_SEQUENCE);
    }

    @Test
    void 미결제_주문은_취소하고_이력과_CANCELED_결과를_함께_남긴다() {
        Order order = placedOrders.place(customerId);

        settlement.settle(cancel(order, CancelReason.EXPIRY));

        assertThat(statusOf(order)).isEqualTo("CANCELED");
        assertThat(jdbcTemplate.queryForMap("""
                SELECT event_sequence, from_status, to_status, actor, reason
                  FROM order_events WHERE order_id = ? ORDER BY event_sequence DESC LIMIT 1
                """, order.id()))
                .containsEntry("event_sequence", 2L)
                .containsEntry("from_status", "AWAITING_PAYMENT")
                .containsEntry("to_status", "CANCELED")
                .containsEntry("actor", "SYSTEM")
                .containsEntry("reason", "PREORDER_CANCEL:EXPIRY");
        assertThat(results(order)).containsExactly("CANCELED");
    }

    /** SQS 는 최소 1회 전달이다. 전이는 한 번, 결과는 받을 때마다 같은 값으로 다시 적는다(응답 2개 이하). */
    @Test
    void 같은_요청을_다시_받으면_전이는_한_번이고_같은_결과를_다시_적는다() {
        Order order = placedOrders.place(customerId);
        SettlePreorderCancelCommand cancel = cancel(order, CancelReason.USER);

        settlement.settle(cancel);
        settlement.settle(cancel);

        assertThat(eventCount(order)).isEqualTo(2);
        assertThat(results(order)).containsExactly("CANCELED", "CANCELED");
        assertThat(outboxRows(order.preorderId()))
                .allSatisfy(row -> assertThat(payloadOf(row).get("cancelSequence").asLong()).isEqualTo(CANCEL_SEQUENCE));
    }

    @Test
    void 같은_요청이_동시에_와도_전이는_한_번이고_모두_성공한다() throws Exception {
        Order order = placedOrders.place(customerId);
        SettlePreorderCancelCommand cancel = cancel(order, CancelReason.USER);

        List<Outcome<Result>> outcomes = Concurrently.run(REQUESTS, i -> () -> settlement.settle(cancel).result());

        assertThat(outcomes).allSatisfy(outcome -> {
            assertThat(outcome.error()).isNull();
            assertThat(outcome.value()).isEqualTo(Result.CANCELED);
        });
        assertThat(eventCount(order)).isEqualTo(2);
        assertThat(results(order)).hasSize(REQUESTS).containsOnly("CANCELED");
    }

    @Test
    void 출고된_주문은_SHIPPED_로_거절하고_상태를_바꾸지_않는다() {
        Order order = placedOrders.place(customerId);
        fixtures.forceStatus(order.id(), "SHIPPED");

        settlement.settle(cancel(order, CancelReason.USER));

        assertThat(statusOf(order)).isEqualTo("SHIPPED");
        assertThat(eventCount(order)).isEqualTo(1);
        assertThat(outboxRows(order.preorderId())).singleElement().satisfies(row -> {
            assertThat(payloadOf(row).get("result").asString()).isEqualTo("REJECTED");
            assertThat(payloadOf(row).get("reason").asString()).isEqualTo(RejectReason.SHIPPED.name());
        });
    }

    @Test
    void 결제된_주문의_만료_취소는_PAID_로_거절하고_상태를_바꾸지_않는다() {
        Order order = placedOrders.place(customerId);
        fixtures.forceStatus(order.id(), "AWAITING_CONFIRMATION");

        settlement.settle(cancel(order, CancelReason.EXPIRY));

        assertThat(statusOf(order)).isEqualTo("AWAITING_CONFIRMATION");
        assertThat(eventCount(order)).isEqualTo(1);
        assertThat(outboxRows(order.preorderId())).singleElement().satisfies(row -> {
            assertThat(payloadOf(row).get("result").asString()).isEqualTo("REJECTED");
            assertThat(payloadOf(row).get("reason").asString()).isEqualTo(RejectReason.PAID.name());
        });
    }

    /** 결제됨 + 만료 외 · 승인 결과 대기 · 환불 중: 결과를 적지 않고 예외로 되돌린다 — 메시지는 지워지지 않는다. */
    @ParameterizedTest
    @ValueSource(strings = {"AWAITING_CONFIRMATION", "AUTHORIZING", "CANCELING"})
    void 결과를_정할_수_없으면_아무것도_적지_않고_실패한다(String status) {
        Order order = placedOrders.place(customerId);
        fixtures.forceStatus(order.id(), status);

        assertThatThrownBy(() -> settlement.settle(cancel(order, CancelReason.USER)))
                .isInstanceOf(SettlementDeferredException.class);

        assertThat(statusOf(order)).isEqualTo(status);
        assertThat(eventCount(order)).isEqualTo(1);
        assertThat(outboxRows(order.preorderId())).isEmpty();
    }

    /*
     * 주문은 봉투의 aggregateId 로 찾고, payload 의 UUID 는 그 주문의 preorder_token 과 대조한다. 다르면 봉투와 payload 가
     * 다른 예약을 가리킨다 — 어느 예약의 결과인지 알 수 없으니 주문을 건드리지도 결과를 적지도 않는다(소비기가 DLQ 로 보낸다).
     */
    @Test
    void payload_의_예약_UUID_가_주문의_예약과_다르면_아무것도_바꾸지_않고_실패한다() {
        Order order = placedOrders.place(customerId);
        String otherUuid = OrderFixtures.unique();

        assertThatThrownBy(() -> settlement.settle(new SettlePreorderCancelCommand(order.preorderId(), otherUuid,
                customerId, CancelReason.USER, CANCEL_SEQUENCE)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(otherUuid);

        assertThat(statusOf(order)).isEqualTo("AWAITING_PAYMENT");
        assertThat(eventCount(order)).isEqualTo(1);
        assertThat(outboxRows(order.preorderId())).isEmpty();
    }

    // 주문이 저장한 UUID 는 예약의 것과 같다 — 그래서 정상 수신(uuidOf)은 대조를 통과한다.
    @Test
    void 주문은_예약의_UUID_를_저장한다() {
        Order order = placedOrders.place(customerId);

        assertThat(order.preorderToken()).isEqualTo(uuidOf(order.preorderId()));
    }

    @Test
    void 예약의_회원과_주문의_회원이_다르면_아무것도_바꾸지_않고_실패한다() {
        Order order = placedOrders.place(customerId);

        assertThatThrownBy(() -> settlement.settle(new SettlePreorderCancelCommand(order.preorderId(),
                uuidOf(order.preorderId()), fixtures.customer(), CancelReason.USER, CANCEL_SEQUENCE)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(statusOf(order)).isEqualTo("AWAITING_PAYMENT");
        assertThat(eventCount(order)).isEqualTo(1);
        assertThat(outboxRows(order.preorderId())).isEmpty();
    }

    private SettlePreorderCancelCommand cancel(Order order, CancelReason reason) {
        return cancel(order.preorderId(), uuidOf(order.preorderId()), reason);
    }

    private SettlePreorderCancelCommand cancel(Long preorderId, String preorderUuid, CancelReason reason) {
        return new SettlePreorderCancelCommand(preorderId, preorderUuid, customerId, reason, CANCEL_SEQUENCE);
    }

    private String uuidOf(Long preorderId) {
        return jdbcTemplate.queryForObject("SELECT preorder_token FROM preorders WHERE id = ?", String.class,
                preorderId);
    }

    private String statusOf(Order order) {
        return jdbcTemplate.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, order.id());
    }

    private int eventCount(Order order) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM order_events WHERE order_id = ?", Integer.class,
                order.id());
    }

    private List<Map<String, Object>> outboxRows(Long preorderId) {
        return jdbcTemplate.queryForList("""
                SELECT event_type, aggregate_type, aggregate_id, payload
                  FROM outbox_events WHERE aggregate_type = 'PREORDER' AND aggregate_id = ? ORDER BY id
                """, preorderId);
    }

    private List<String> results(Order order) {
        return outboxRows(order.preorderId()).stream()
                .map(row -> payloadOf(row).get("result").asString())
                .toList();
    }

    private JsonNode payloadOf(Map<String, Object> row) {
        return jsonMapper.readTree((String) row.get("payload"));
    }
}
