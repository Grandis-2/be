package com.grandis.nova.order.order.pay;

import com.grandis.nova.common.testing.Concurrently;
import com.grandis.nova.common.testing.Concurrently.Outcome;
import com.grandis.nova.order.client.payment.DeclineReason;
import com.grandis.nova.order.order.OrderLedger;
import com.grandis.nova.order.order.cancel.CancelReason;
import com.grandis.nova.order.order.cancel.SettlePreorderCancelCommand;
import com.grandis.nova.order.order.cancel.SettlePreorderCancelService;
import com.grandis.nova.order.order.domain.model.Order;
import com.grandis.nova.order.order.domain.model.OrderTransition;
import com.grandis.nova.order.order.domain.repository.OrderReader;
import com.grandis.nova.order.order.vo.EventCause;
import com.grandis.nova.order.support.OrderFixtures;
import com.grandis.nova.order.support.OrderIntegrationTest;
import com.grandis.nova.order.support.PlacedOrders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static com.grandis.nova.order.support.OrderFixtures.bytes;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 사전예약 주문의 결제 승인 알림(PREORDER_PAYMENT_CONFIRMED)과 결제 시각(orders.paid_at), 그리고 취소 뒤 승인 없음.
 * 결제 결과 반영이 트랜잭션을 열고 커밋하므로 결과는 DB 에서 직접 읽는다. 테스트마다 새 예약을 써서 아웃박스 행이 겹치지 않는다.
 */
@OrderIntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class PreorderPaymentEventsTest {

    static final Instant APPROVED_AT = Instant.parse("2026-10-16T03:00:00.123456Z");
    static final String CONFIRMED = "PREORDER_PAYMENT_CONFIRMED";
    static final String UNRECEIVED_APPROVAL = "결제가 승인됐는데 주문이 받지 못했다";
    static final int RESULTS = 5;

    @Autowired
    PaymentResults results;

    @Autowired
    SettlePreorderCancelService cancelSettlement;

    @Autowired
    OrderLedger ledger;

    @Autowired
    OrderReader orderReader;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    JsonMapper jsonMapper;

    OrderFixtures fixtures;
    PlacedOrders placedOrders;
    UUID customerId;
    Order order;
    String attempt;

    @BeforeEach
    void setUp() {
        fixtures = new OrderFixtures(jdbcTemplate);
        placedOrders = new PlacedOrders(ledger, transactionTemplate, fixtures);
        customerId = fixtures.customer();
        order = placedOrders.place(customerId);
        attempt = OrderFixtures.unique();
    }

    @Test
    void 결과_이벤트로_승인하면_승인_시각을_결제_시각으로_적고_CONFIRMED_를_한_건_적는다() {
        authorize();

        results.settle(approval(APPROVED_AT));

        assertThat(statusOf()).isEqualTo("AWAITING_CONFIRMATION");
        assertThat(paidAt()).isEqualTo(APPROVED_AT);
        assertThat(jdbcTemplate.queryForMap("""
                SELECT event_type, aggregate_type, BIN_TO_UUID(aggregate_id) AS aggregate_id
                  FROM order_outbox_events WHERE aggregate_type = 'PREORDER' AND aggregate_id = ? AND event_type = ?
                """, bytes(order.preorderId()), CONFIRMED))
                .containsEntry("aggregate_id", order.preorderId().toString());
        JsonNode payload = confirmedPayloads().getFirst();
        assertThat(payload.propertyNames()).containsExactlyInAnyOrder("preorderId", "orderId", "paidAt");
        assertThat(payload.get("preorderId").asString()).isEqualTo(order.preorderToken());
        assertThat(payload.get("orderId").asString()).isEqualTo(order.id().toString());
        assertThat(payload.get("paidAt").asString()).isEqualTo("2026-10-16T03:00:00.123456Z");
    }

    /** 승인 API 응답에는 승인 시각이 없다 — 반영한 시각(승인 반영 이력의 시각)이 결제 시각이고, 알림도 그 값이다. */
    @Test
    void 승인_응답으로_반영하면_반영한_시각이_결제_시각이다() {
        authorize();

        results.approved(order.id(), attempt);

        Instant reflectedAt = jdbcTemplate.queryForObject("""
                SELECT created_at FROM order_events
                 WHERE order_id = ? AND from_status = 'AUTHORIZING' AND to_status = 'AWAITING_CONFIRMATION'
                """, LocalDateTime.class, bytes(order.id())).toInstant(ZoneOffset.UTC);
        assertThat(paidAt()).isEqualTo(reflectedAt);
        assertThat(Instant.parse(confirmedPayloads().getFirst().get("paidAt").asString())).isEqualTo(reflectedAt);
    }

    /** 승인 응답과 결과 이벤트가 겹치고 이벤트가 중복으로 와도 승인은 한 번 반영된다 — 결제 시각은 처음 값, 알림은 한 건. */
    @Test
    void 승인이_겹치고_중복으로_와도_결제_시각과_알림은_한_번이다() throws Exception {
        authorize();
        results.approved(order.id(), attempt);
        Instant first = paidAt();

        List<Outcome<OrderTransition>> outcomes =
                Concurrently.run(RESULTS, i -> () -> results.settle(approval(APPROVED_AT)));

        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome.error()).isNull());
        assertThat(paidAt()).isEqualTo(first);
        assertThat(confirmedPayloads()).hasSize(1);
    }

    @Test
    void 결과_이벤트가_동시에_와도_알림은_한_건이다() throws Exception {
        authorize();

        Concurrently.run(RESULTS, i -> () -> results.settle(approval(APPROVED_AT)));

        assertThat(statusOf()).isEqualTo("AWAITING_CONFIRMATION");
        assertThat(confirmedPayloads()).hasSize(1);
    }

    /** 알림은 승인 반영과 같은 트랜잭션이다 — 반영이 되돌아가면 결제 시각도 알림도 남지 않는다. */
    @Test
    void 승인_반영이_되돌아가면_결제_시각도_알림도_없다() {
        authorize();

        transactionTemplate.executeWithoutResult(status -> {
            results.approved(order.id(), attempt);
            status.setRollbackOnly();
        });

        assertThat(statusOf()).isEqualTo("AUTHORIZING");
        assertThat(paidAt()).isNull();
        assertThat(confirmedPayloads()).isEmpty();
    }

    /**
     * 취소 뒤 승인 없음: 만료 취소는 결제 대기에서만 되고, 결제 대기로 돌아가는 것은 그 결제창이 확정 거절됐을 때뿐이다. 그래도 늦은 승인이
     * 오면 취소된 주문은 승인으로 바꾸지 않는다 — 결제 시각 · 알림 없이 ERROR 로 알린다(D18, 운영 대조로 환불).
     */
    @Test
    void 만료_취소_뒤_늦게_온_승인은_반영하지_않고_알리지_않는다(CapturedOutput output) {
        cancelSettlement.settle(expiry());
        assertThat(statusOf()).isEqualTo("CANCELED");

        results.settle(approval(APPROVED_AT));

        assertThat(statusOf()).isEqualTo("CANCELED");
        assertThat(paidAt()).isNull();
        assertThat(confirmedPayloads()).isEmpty();
        assertThat(output.getAll()).contains(UNRECEIVED_APPROVAL);
    }

    /** 결과 불명인 동안(승인 중)에는 만료 취소를 보류한다. 그 결제창이 거절된 뒤에야 결제 대기로 돌아가 취소된다. */
    @Test
    void 승인_중이면_만료_취소를_보류하고_거절된_뒤에야_취소된다(CapturedOutput output) {
        authorize();
        cancelSettlement.settle(expiry());
        assertThat(statusOf()).as("결과 불명 — 보류").isEqualTo("AUTHORIZING");

        results.settle(new PaymentSettlement(order.id(), attempt, PaymentSettlement.Result.DECLINED, amount(), null,
                DeclineReason.PAYMENT_EXPIRED));
        cancelSettlement.settle(expiry());
        assertThat(statusOf()).isEqualTo("CANCELED");

        results.settle(approval(APPROVED_AT));

        assertThat(statusOf()).isEqualTo("CANCELED");
        assertThat(confirmedPayloads()).isEmpty();
        assertThat(output.getAll()).contains(UNRECEIVED_APPROVAL);
    }

    /** 승인 중에 보류한 만료 취소가 승인 뒤 다시 오면 결제됨으로 거절하고, 결제 시각을 싣는다(preorder 가 확정으로 되돌린다). */
    @Test
    void 보류한_만료_취소가_승인_뒤_다시_오면_결제_시각과_함께_거절한다() {
        authorize();
        cancelSettlement.settle(expiry());

        results.settle(approval(APPROVED_AT));
        cancelSettlement.settle(expiry());

        JsonNode settled = jsonMapper.readTree(jdbcTemplate.queryForObject("""
                SELECT payload FROM order_outbox_events
                 WHERE aggregate_type = 'PREORDER' AND aggregate_id = ? AND event_type = 'PREORDER_ORDER_SETTLED'
                """, String.class, bytes(order.preorderId())));
        assertThat(settled.get("result").asString()).isEqualTo("REJECTED");
        assertThat(settled.get("reason").asString()).isEqualTo("PAID");
        assertThat(Instant.parse(settled.get("paidAt").asString())).isEqualTo(APPROVED_AT);
    }

    /**
     * 만료 경합: 정리가 주문을 잠그지 않고 읽은 뒤(결제 시각 없음 — 영속성 컨텍스트에 남는다) 잠그기 전에 승인이 커밋된다. 거절(PAID)의
     * 결제 시각은 잠근 뒤 DB 에서 다시 읽은 값이어야 한다 — 읽어 둔 주문의 값이면 비어 나가 preorder 가 미결제로 되돌려 다시 만료한다.
     * 바깥 트랜잭션에서 먼저 읽어 두고, 그 트랜잭션에 참여한 정리가 같은 주문을 다시 읽게 해 그 순간을 만든다.
     */
    @Test
    void 잠그기_전에_반영된_승인의_결제_시각을_거절에_싣는다() {
        authorize();

        transactionTemplate.executeWithoutResult(status -> {
            assertThat(orderReader.findByPreorderId(order.preorderId()).orElseThrow().paidAt()).isNull();
            CompletableFuture.runAsync(() -> results.settle(approval(APPROVED_AT))).join();
            cancelSettlement.settle(expiry());
        });

        JsonNode settled = jsonMapper.readTree(jdbcTemplate.queryForObject("""
                SELECT payload FROM order_outbox_events
                 WHERE aggregate_type = 'PREORDER' AND aggregate_id = ? AND event_type = 'PREORDER_ORDER_SETTLED'
                """, String.class, bytes(order.preorderId())));
        assertThat(settled.get("reason").asString()).isEqualTo("PAID");
        assertThat(Instant.parse(settled.get("paidAt").asString())).isEqualTo(APPROVED_AT);
    }

    private void authorize() {
        transactionTemplate.executeWithoutResult(status -> ledger.requestPayment(order.id(), attempt, EventCause.user()));
    }

    private PaymentSettlement approval(Instant approvedAt) {
        return new PaymentSettlement(order.id(), attempt, PaymentSettlement.Result.APPROVED, amount(), approvedAt, null);
    }

    private BigDecimal amount() {
        return order.totalAmount().amount();
    }

    private SettlePreorderCancelCommand expiry() {
        return new SettlePreorderCancelCommand(order.preorderId(), order.preorderToken(), customerId, CancelReason.EXPIRY, 1L);
    }

    private String statusOf() {
        return jdbcTemplate.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, bytes(order.id()));
    }

    private Instant paidAt() {
        LocalDateTime paidAt = jdbcTemplate.queryForObject("SELECT paid_at FROM orders WHERE id = ?", LocalDateTime.class,
                bytes(order.id()));
        return paidAt == null ? null : paidAt.toInstant(ZoneOffset.UTC);
    }

    private List<JsonNode> confirmedPayloads() {
        return jdbcTemplate.queryForList("""
                SELECT payload FROM order_outbox_events
                 WHERE aggregate_type = 'PREORDER' AND aggregate_id = ? AND event_type = ? ORDER BY id
                """, String.class, bytes(order.preorderId()), CONFIRMED).stream().map(jsonMapper::readTree).toList();
    }
}
