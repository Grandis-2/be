package com.grandis.nova.order.order.cancel;

import com.grandis.nova.order.order.OrderLedger;
import com.grandis.nova.order.order.domain.enums.OrderSource;
import com.grandis.nova.order.order.domain.enums.OrderStatus;
import com.grandis.nova.order.order.domain.enums.OrderTrigger;
import com.grandis.nova.order.order.domain.model.Order;
import com.grandis.nova.order.order.domain.model.OrderDraft;
import com.grandis.nova.order.order.domain.model.OrderLine;
import com.grandis.nova.order.order.pay.PaymentResults;
import com.grandis.nova.order.order.pay.PaymentSettlement;
import com.grandis.nova.order.order.vo.EventCause;
import com.grandis.nova.order.order.vo.Money;
import com.grandis.nova.order.order.vo.Quantity;
import com.grandis.nova.order.order.vo.ShipTo;
import com.grandis.nova.order.support.OrderFixtures;
import com.grandis.nova.order.support.OrderIntegrationTest;
import com.grandis.nova.order.support.PlacedOrders;
import com.grandis.nova.order.support.TestAuth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.grandis.nova.order.support.OrderFixtures.bytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 환불 결과 반영(ORDER_REFUND_SETTLED). 결과 하나가 트랜잭션 하나로 커밋되므로 결과는 DB 와 조회 API 로 본다.
 * 같은 결과를 다시 받아도(최소 1회 전달) 한 번만 반영된다.
 */
@OrderIntegrationTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class RefundResultsTest {

    static final long CANCEL_SEQUENCE = 3L;

    @Autowired
    RefundResults refundResults;

    @Autowired
    PaymentResults paymentResults;

    @Autowired
    SettlePreorderCancelService cancelSettlement;

    @Autowired
    JsonMapper jsonMapper;

    @Autowired
    OrderLedger ledger;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    MockMvc mockMvc;

    OrderFixtures fixtures;
    PlacedOrders orders;
    UUID customerId;

    @BeforeEach
    void setUp() {
        fixtures = new OrderFixtures(jdbcTemplate);
        orders = new PlacedOrders(ledger, transactionTemplate, fixtures);
        customerId = fixtures.customer();
    }

    @Test
    void refundedCancelingOrderIsCanceledOnce() {
        Order order = canceling();

        refundResults.settle(refunded(order));
        refundResults.settle(refunded(order));

        assertThat(statusOf(order)).isEqualTo("CANCELED");
        assertThat(lastEvent(order)).containsEntry("from_status", "CANCELING").containsEntry("to_status", "CANCELED")
                .containsEntry("reason", "REFUND_COMPLETED");
        assertThat(eventCount(order)).isEqualTo(5);
    }

    // U1: 환불이 확정 실패하면 취소 중에 둔 채 실패 시각을 적고 이력에 한 줄 남긴다 — 같은 알림을 다시 받아도 한 번이다
    @Test
    void failedRefundKeepsCancelingAndNotesOnce() {
        Order order = canceling();

        refundResults.settle(failed(order));
        LocalDateTime firstNoted = refundFailedAtOf(order);
        refundResults.settle(failed(order));

        assertThat(statusOf(order)).isEqualTo("CANCELING");
        assertThat(firstNoted).isNotNull();
        assertThat(refundFailedAtOf(order)).isEqualTo(firstNoted);
        assertThat(lastEvent(order)).containsEntry("from_status", "CANCELING").containsEntry("to_status", "CANCELING")
                .containsEntry("actor", "SYSTEM").containsEntry("reason", EventCause.REFUND_FAILED);
        assertThat(eventCount(order)).isEqualTo(5);
    }

    // U1: 사용자 · 관리자 상세에 환불 실패 표시만 — 오류 코드는 없다(문구는 프론트)
    @Test
    void orderDetailShowsRefundFailure() throws Exception {
        Order order = canceling();
        Order refunding = canceling();
        refundResults.settle(failed(order));

        mockMvc.perform(get("/api/v1/orders/{orderId}", order.id().toString()).with(TestAuth.customer(customerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELING"))
                .andExpect(jsonPath("$.data.refundFailed").value(true));
        mockMvc.perform(get("/api/v1/admin/orders/{orderId}", order.id().toString()).with(TestAuth.admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.refundFailed").value(true));
        mockMvc.perform(get("/api/v1/orders/{orderId}", refunding.id().toString())
                        .with(TestAuth.customer(customerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.refundFailed").value(false));
    }

    // 환불 실패 뒤 사람이 해소해 환불이 끝나면(다시 연 환불의 완료) 취소로 끝나고 실패 표시가 사라진다
    @Test
    void refundCompletedAfterFailureCancelsAndClearsFailure() throws Exception {
        Order order = canceling();
        refundResults.settle(failed(order));

        refundResults.settle(refunded(order));

        assertThat(statusOf(order)).isEqualTo("CANCELED");
        mockMvc.perform(get("/api/v1/orders/{orderId}", order.id().toString()).with(TestAuth.customer(customerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.refundFailed").value(false));
    }

    // 환불은 취소 중에만 요청된다 — 다른 상태에 완료가 오면 버그 · 수동 수정의 신호라 경보만 하고 바꾸지 않는다
    @Test
    void refundOfOrderNotCancelingChangesNothingAndAlerts(CapturedOutput output) {
        Order order = orders.place(customerId);
        orders.pay(order.id());

        refundResults.settle(refunded(order));

        assertThat(statusOf(order)).isEqualTo("AWAITING_CONFIRMATION");
        assertThat(output.getAll()).contains("환불이 끝났는데 주문이 취소 중이 아니다");
    }

    @Test
    void mismatchedRefundAmountIsReportedButApplied(CapturedOutput output) {
        Order order = canceling();

        refundResults.settle(new RefundSettlement(order.id(), RefundSettlement.Result.REFUNDED, BigDecimal.ONE));

        assertThat(statusOf(order)).isEqualTo("CANCELED");
        assertThat(output.getAll()).contains("환불 결과의 금액이 주문 총액과 다르다");
    }

    // D17: 환불로 끝난 주문에 늦게 온 승인은 중복이다 — "돈 나갔는데 미결제" 경보를 내지 않는다
    @Test
    void lateApprovalAfterRefundIsIgnoredWithoutAlert(CapturedOutput output) {
        Order order = canceling();
        refundResults.settle(refunded(order));

        paymentResults.settle(new PaymentSettlement(order.id(), "toss-late", PaymentSettlement.Result.APPROVED,
                order.totalAmount().amount(), Instant.parse("2026-10-16T03:00:00Z"), null));

        assertThat(statusOf(order)).isEqualTo("CANCELED");
        assertThat(output.getAll()).doesNotContain("결제가 승인됐는데 주문이 받지 못했다");
    }

    // 결제 없이 취소된 주문에 온 승인은 여전히 경보다(D18 — 자동 환불하지 않는다)
    @Test
    void approvalForUnpaidCanceledOrderStillAlerts(CapturedOutput output) {
        Order order = orders.place(customerId);
        orders.cancel(order.id(), "EXPIRY");

        paymentResults.settle(new PaymentSettlement(order.id(), "toss-late", PaymentSettlement.Result.APPROVED,
                order.totalAmount().amount(), Instant.parse("2026-10-16T03:00:00Z"), null));

        assertThat(output.getAll()).contains("결제가 승인됐는데 주문이 받지 못했다");
    }

    // 결제된 사전예약 취소의 환불이 끝나면 같은 트랜잭션에서 저장한 순번으로 CANCELED 를 알린다 — 같은 결과를 다시 받아도 한 번이다
    @Test
    void refundOfPaidPreorderCancelReportsCanceledOnce() {
        Order order = paidPreorderCanceling();

        refundResults.settle(refunded(order));
        refundResults.settle(refunded(order));

        assertThat(settledRows(order)).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("aggregate_type", "PREORDER").containsEntry("aggregate_id", order.preorderId().toString());
            JsonNode payload = jsonMapper.readTree((String) row.get("payload"));
            assertThat(payload.get("preorderId").asString()).isEqualTo(order.preorderToken());
            assertThat(payload.get("result").asString()).isEqualTo("CANCELED");
            assertThat(payload.get("cancelSequence").asLong()).isEqualTo(CANCEL_SEQUENCE);
            assertThat(payload.get("reason").isNull()).isTrue();
            assertThat(payload.get("paidAt").isNull()).isTrue();
        });
        assertThat(reportedAtOf(order)).isNotNull();
    }

    // 알림은 취소 전이와 한 트랜잭션이다 — 전이가 롤백되면 알림도 표식도 남지 않는다
    @Test
    void canceledReportRollsBackWithTransition() {
        Order order = paidPreorderCanceling();

        transactionTemplate.executeWithoutResult(status -> {
            refundResults.settle(refunded(order));
            status.setRollbackOnly();
        });

        assertThat(statusOf(order)).isEqualTo("CANCELING");
        assertThat(settledRows(order)).isEmpty();
        assertThat(reportedAtOf(order)).isNull();
    }

    // 환불 확정 실패는 알리지 않는다(U1). 사람이 해소해 환불이 끝나면 그때 한 번 알린다
    @Test
    void failedRefundReportsNothingUntilRefunded() {
        Order order = paidPreorderCanceling();

        refundResults.settle(failed(order));
        assertThat(settledRows(order)).isEmpty();
        assertThat(reportedAtOf(order)).isNull();

        refundResults.settle(refunded(order));
        assertThat(settledRows(order)).hasSize(1);
        assertThat(reportedAtOf(order)).isNotNull();
    }

    @Test
    void refundOfCartOrderReportsNothing() {
        Order cart = transactionTemplate.execute(status -> ledger.place(cartDraft(), EventCause.user()));
        fixtures.forceStatus(cart.id(), "CANCELING");
        int before = settledCount();

        refundResults.settle(refunded(cart));

        assertThat(statusOf(cart)).isEqualTo("CANCELED");
        assertThat(settledCount()).isEqualTo(before);
    }

    // 순번이 없는 취소 중(이 변경 전에 취소 중이 된 주문)은 환불이 끝나도 알리지 않는다 — 늦춰 다시 받은 요청이 CANCELED 를 적는다
    @Test
    void refundWithoutStoredSequenceLeavesResultToRedelivery(CapturedOutput output) {
        Order order = canceling();

        refundResults.settle(refunded(order));

        assertThat(statusOf(order)).isEqualTo("CANCELED");
        assertThat(settledRows(order)).isEmpty();
        assertThat(output.getAll()).contains("취소 시도 순번이 없어 취소 결과를 알리지 않음");

        cancelSettlement.settle(new SettlePreorderCancelCommand(order.preorderId(), order.preorderToken(), customerId,
                CancelReason.USER, CANCEL_SEQUENCE));
        assertThat(settledRows(order)).singleElement().satisfies(row ->
                assertThat(jsonMapper.readTree((String) row.get("payload")).get("result").asString()).isEqualTo("CANCELED"));
    }

    /** 결제 → 취소 요청으로 취소 중(이력 4줄: 생성 · 결제 요청 · 승인 · 취소 중). */
    private Order canceling() {
        Order order = orders.place(customerId);
        orders.pay(order.id());
        transactionTemplate.executeWithoutResult(status -> ledger.fire(order.id(), OrderTrigger.CANCEL_REQUESTED,
                EnumSet.of(OrderStatus.AWAITING_CONFIRMATION), EventCause.system("PREORDER_CANCEL:USER")));
        return order;
    }

    /** 결제 → 예약 취소 수신으로 취소 중. 순번을 주문에 적는다. */
    private Order paidPreorderCanceling() {
        Order order = orders.place(customerId);
        orders.pay(order.id());
        cancelSettlement.settle(new SettlePreorderCancelCommand(order.preorderId(), order.preorderToken(), customerId,
                CancelReason.USER, CANCEL_SEQUENCE));
        return order;
    }

    private OrderDraft cartDraft() {
        OrderFixtures.StockProduct stocked = fixtures.inStockProduct(1);
        return new OrderDraft(customerId, OrderSource.CART, null, null, null,
                new ShipTo("홍길동", "010-0000-0000", "04524", "세종대로 110", null),
                List.of(new OrderLine(stocked.productId(), stocked.optionIds().getFirst(), new Quantity(1), Money.won(1_000), 0,
                        Money.ZERO, "상품", null, "옵션", null)));
    }

    private List<Map<String, Object>> settledRows(Order order) {
        return jdbcTemplate.queryForList("""
                SELECT aggregate_type, BIN_TO_UUID(aggregate_id) AS aggregate_id, payload FROM order_outbox_events
                 WHERE event_type = 'PREORDER_ORDER_SETTLED' AND aggregate_id = ? ORDER BY id
                """, bytes(order.preorderId()));
    }

    private int settledCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM order_outbox_events WHERE event_type = 'PREORDER_ORDER_SETTLED'",
                Integer.class);
    }

    private LocalDateTime reportedAtOf(Order order) {
        return jdbcTemplate.queryForObject("SELECT preorder_cancel_reported_at FROM orders WHERE id = ?", LocalDateTime.class,
                bytes(order.id()));
    }

    private static RefundSettlement refunded(Order order) {
        return new RefundSettlement(order.id(), RefundSettlement.Result.REFUNDED, order.totalAmount().amount());
    }

    private static RefundSettlement failed(Order order) {
        return new RefundSettlement(order.id(), RefundSettlement.Result.FAILED, order.totalAmount().amount());
    }

    private String statusOf(Order order) {
        return jdbcTemplate.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, bytes(order.id()));
    }

    private LocalDateTime refundFailedAtOf(Order order) {
        return jdbcTemplate.queryForObject("SELECT refund_failed_at FROM orders WHERE id = ?", LocalDateTime.class,
                bytes(order.id()));
    }

    private int eventCount(Order order) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM order_events WHERE order_id = ?", Integer.class,
                bytes(order.id()));
    }

    private Map<String, Object> lastEvent(Order order) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT from_status, to_status, actor, reason FROM order_events
                 WHERE order_id = ? ORDER BY event_sequence DESC LIMIT 1
                """, bytes(order.id()));
        return rows.getFirst();
    }
}
