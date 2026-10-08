package com.grandis.nova.order.order.cancel;

import com.grandis.nova.order.order.OrderLedger;
import com.grandis.nova.order.order.domain.enums.OrderStatus;
import com.grandis.nova.order.order.domain.enums.OrderTrigger;
import com.grandis.nova.order.order.domain.model.Order;
import com.grandis.nova.order.order.pay.PaymentResults;
import com.grandis.nova.order.order.pay.PaymentSettlement;
import com.grandis.nova.order.order.vo.EventCause;
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

import java.math.BigDecimal;
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

    @Autowired
    RefundResults refundResults;

    @Autowired
    PaymentResults paymentResults;

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

    // U1: 환불이 확정 실패하면 취소 중에 둔 채 실패를 이력에 한 줄 남긴다 — 같은 알림을 다시 받아도 한 줄이다
    @Test
    void failedRefundKeepsCancelingAndNotesOnce() {
        Order order = canceling();

        refundResults.settle(failed(order));
        refundResults.settle(failed(order));

        assertThat(statusOf(order)).isEqualTo("CANCELING");
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

        mockMvc.perform(get("/api/v1/orders/{orderId}", order.orderToken().value()).with(TestAuth.customer(customerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELING"))
                .andExpect(jsonPath("$.data.refundFailed").value(true));
        mockMvc.perform(get("/api/v1/admin/orders/{orderId}", order.orderToken().value()).with(TestAuth.admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.refundFailed").value(true));
        mockMvc.perform(get("/api/v1/orders/{orderId}", refunding.orderToken().value())
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
        mockMvc.perform(get("/api/v1/orders/{orderId}", order.orderToken().value()).with(TestAuth.customer(customerId)))
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
                order.totalAmount().amount(), null));

        assertThat(statusOf(order)).isEqualTo("CANCELED");
        assertThat(output.getAll()).doesNotContain("결제가 승인됐는데 주문이 받지 못했다");
    }

    // 결제 없이 취소된 주문에 온 승인은 여전히 경보다(D18 — 자동 환불하지 않는다)
    @Test
    void approvalForUnpaidCanceledOrderStillAlerts(CapturedOutput output) {
        Order order = orders.place(customerId);
        orders.cancel(order.id(), "EXPIRY");

        paymentResults.settle(new PaymentSettlement(order.id(), "toss-late", PaymentSettlement.Result.APPROVED,
                order.totalAmount().amount(), null));

        assertThat(output.getAll()).contains("결제가 승인됐는데 주문이 받지 못했다");
    }

    /** 결제 → 취소 요청으로 취소 중(이력 4줄: 생성 · 결제 요청 · 승인 · 취소 중). */
    private Order canceling() {
        Order order = orders.place(customerId);
        orders.pay(order.id());
        transactionTemplate.executeWithoutResult(status -> ledger.fire(order.id(), OrderTrigger.CANCEL_REQUESTED,
                EnumSet.of(OrderStatus.AWAITING_CONFIRMATION), EventCause.system("PREORDER_CANCEL:USER")));
        return order;
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
