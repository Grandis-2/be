package com.grandis.nova.order.order.api;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.order.OrderErrorCode;
import com.grandis.nova.order.client.payment.PaymentRefundAdmin;
import com.grandis.nova.order.client.payment.PaymentRefundAdminRequest;
import com.grandis.nova.order.client.payment.PaymentRefundAdminResult;
import com.grandis.nova.order.client.payment.PaymentRefundIssue;
import com.grandis.nova.order.order.OrderLedger;
import com.grandis.nova.order.order.cancel.RefundResults;
import com.grandis.nova.order.order.cancel.RefundSettlement;
import com.grandis.nova.order.order.domain.enums.OrderStatus;
import com.grandis.nova.order.order.domain.enums.OrderTrigger;
import com.grandis.nova.order.order.domain.model.Order;
import com.grandis.nova.order.order.vo.EventCause;
import com.grandis.nova.order.support.OrderFixtures;
import com.grandis.nova.order.support.OrderIntegrationTest;
import com.grandis.nova.order.support.PlacedOrders;
import com.grandis.nova.order.support.TestAuth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.grandis.nova.order.support.OrderFixtures.bytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 멈춘 환불의 관리자 목록 · 조치(/api/v1/admin/orders/refund-issues · /{orderId}/refund/**). payment 중계는 대역이다(계약은
 * PaymentRefundAdminTest). 주문 쪽 판정 · 두 쪽 합치기 · 다시 보내기 뒤 주문 기록을 본다. DB 를 공유하므로 목록 단정은 이 테스트가 만든 주문으로만 한다.
 */
@OrderIntegrationTest
@AutoConfigureMockMvc
class AdminRefundIssueApiTest {

    @Autowired MockMvc mockMvc;
    @Autowired OrderLedger ledger;
    @Autowired RefundResults refundResults;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired JdbcTemplate jdbcTemplate;
    @MockitoBean PaymentRefundAdmin payments;

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
    void 주문_쪽과_결제_쪽에서_고른_주문을_합쳐_주문의_사실과_결제의_판정을_함께_준다() throws Exception {
        Order failed = canceling();
        refundResults.settle(failed(failed));
        Order refundedAtPayment = canceling();
        refundResults.settle(failed(refundedAtPayment));
        UUID paymentOnly = UUID.randomUUID();
        given(payments.issues(eq(List.of()), anyInt(), any())).willReturn(List.of(issue(paymentOnly, "ESCALATED_MISMATCH")));
        given(payments.issues(argThat(ids -> !ids.isEmpty()), anyInt(), any())).willAnswer(invocation -> {
            List<UUID> ids = invocation.getArgument(0);
            return ids.stream().map(id -> id.equals(refundedAtPayment.id()) ? issue(id, "REFUNDED") : issue(id, "REFUND_REJECTED"))
                    .toList();
        });

        String body = mockMvc.perform(get("/api/v1/admin/orders/refund-issues").with(TestAuth.admin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<Map<String, Object>> items = com.jayway.jsonpath.JsonPath.read(body, "$.data[*]");
        Map<String, Object> mine = byId(items, failed.id());
        assertThat(mine).containsEntry("orderStatus", "CANCELING").containsEntry("reason", "REFUND_REJECTED")
                .containsEntry("paymentStatus", "SUCCEEDED");
        assertThat(mine.get("refundFailedAt")).isNotNull();
        assertThat(mine.get("actions")).isEqualTo(List.of("RETRY", "MANUAL_COMPLETE"));
        assertThat(byId(items, refundedAtPayment.id())).containsEntry("reason", "REFUNDED_NOT_APPLIED");
        assertThat(byId(items, paymentOnly)).containsEntry("reason", "ESCALATED_MISMATCH").containsEntry("orderStatus", null);
        assertThat(items.indexOf(byId(items, paymentOnly))).as("오래 멈춘 것부터 — 결제 쪽 환불 거래가 주문 갱신보다 먼저다")
                .isLessThan(items.indexOf(mine));
    }

    @Test
    void 다시_보내기를_payment_가_받아들이면_주문의_환불_실패를_지워_다시_실패하면_또_적는다() throws Exception {
        Order order = canceling();
        refundResults.settle(failed(order));
        UUID transactionId = UUID.randomUUID();
        given(payments.retry(eq(order.id()), any(), any()))
                .willReturn(new PaymentRefundAdminResult(transactionId, "PENDING", null));

        mockMvc.perform(retry(order.id(), "카드사 확인").with(TestAuth.admin()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.transactionId").value(transactionId.toString()));

        assertThat(refundFailedAtIsNull(order)).isTrue();
        assertThat(lastEvent(order)).containsEntry("reason", "REFUND_RETRIED").containsEntry("actor", "ADMIN");
        mockMvc.perform(get("/api/v1/orders/{orderId}", order.id()).with(TestAuth.customer(customerId)))
                .andExpect(jsonPath("$.data.refundFailed").value(false));

        refundResults.settle(failed(order));
        assertThat(refundFailedAtIsNull(order)).as("다시 실패하면 또 적는다").isFalse();
        mockMvc.perform(get("/api/v1/orders/{orderId}", order.id()).with(TestAuth.customer(customerId)))
                .andExpect(jsonPath("$.data.refundFailed").value(true));
    }

    @Test
    void 다시_보내기를_적기_전에_다시_보낸_환불의_새_실패가_먼저_적혔으면_지우지_않는다() throws Exception {
        Order order = canceling();
        given(payments.retry(eq(order.id()), any(), any())).willAnswer(invocation -> {
            // payment 가 받아들인 뒤 order 가 적기 전에 새 확정 실패가 먼저 반영됐다
            Thread.sleep(5);
            refundResults.settle(failed(order));
            return new PaymentRefundAdminResult(UUID.randomUUID(), "PENDING", null);
        });

        mockMvc.perform(retry(order.id(), "다시").with(TestAuth.admin())).andExpect(status().isAccepted());

        assertThat(refundFailedAtIsNull(order)).as("새 실패는 남는다").isFalse();
        assertThat(lastEvent(order)).containsEntry("reason", "REFUND_FAILED");
        mockMvc.perform(get("/api/v1/orders/{orderId}", order.id()).with(TestAuth.customer(customerId)))
                .andExpect(jsonPath("$.data.refundFailed").value(true));
    }

    @Test
    void payment_가_거절하면_그대로_넘기고_주문은_바꾸지_않는다() throws Exception {
        Order order = canceling();
        refundResults.settle(failed(order));
        given(payments.retry(eq(order.id()), any(), any()))
                .willThrow(new BusinessException(OrderErrorCode.REFUND_NOT_RETRYABLE, Map.of("reason", "PROVIDER_CANCELED")));

        mockMvc.perform(retry(order.id(), "다시").with(TestAuth.admin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("REFUND_NOT_RETRYABLE"))
                .andExpect(jsonPath("$.error.details.reason").value("PROVIDER_CANCELED"));
        assertThat(refundFailedAtIsNull(order)).isFalse();
    }

    @Test
    void 직접_환불_기록은_payment_에_넘기고_결과는_환불_완료_알림이_반영한다() throws Exception {
        Order order = canceling();
        refundResults.settle(failed(order));
        given(payments.manualComplete(eq(order.id()), any(), any()))
                .willReturn(new PaymentRefundAdminResult(UUID.randomUUID(), "SUCCEEDED", Instant.parse("2026-10-24T05:00:00Z")));

        mockMvc.perform(post("/api/v1/admin/orders/{orderId}/refund/manual-complete", order.id()).with(TestAuth.admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedTransactionId\":\"%s\",\"note\":\"직접 취소\"}".formatted(UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"));
        assertThat(lastEvent(order)).containsEntry("reason", "REFUND_FAILED");
    }

    @Test
    void 요청_값과_권한을_가른다() throws Exception {
        mockMvc.perform(get("/api/v1/admin/orders/refund-issues").param("staleHours", "0").with(TestAuth.admin()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(retry(UUID.randomUUID(), " ").with(TestAuth.admin())).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/admin/orders/refund-issues").with(TestAuth.customer(customerId)))
                .andExpect(status().isForbidden());
        mockMvc.perform(retry(UUID.randomUUID(), "x").with(TestAuth.customer(customerId))).andExpect(status().isForbidden());
    }

    // ---- 준비 ----

    /** 결제 → 취소 요청으로 취소 중. */
    private Order canceling() {
        Order order = orders.place(customerId);
        orders.pay(order.id());
        transactionTemplate.executeWithoutResult(status -> ledger.fire(order.id(), OrderTrigger.CANCEL_REQUESTED,
                EnumSet.of(OrderStatus.AWAITING_CONFIRMATION), EventCause.system("PREORDER_CANCEL:USER")));
        return order;
    }

    private static RefundSettlement failed(Order order) {
        return new RefundSettlement(order.id(), RefundSettlement.Result.FAILED, order.totalAmount().amount());
    }

    private static PaymentRefundIssue issue(UUID orderId, String reason) {
        boolean refunded = reason.equals("REFUNDED");
        return new PaymentRefundIssue(orderId, reason, refunded ? "REFUNDED" : "SUCCEEDED",
                new PaymentRefundIssue.Refund(UUID.randomUUID(), refunded ? "SUCCEEDED" : "FAILED", 1,
                        Instant.parse("2026-10-24T01:00:00Z"), Instant.parse("2026-10-24T01:00:05Z"), null,
                        "NOT_CANCELABLE_PAYMENT", null, Instant.parse("2026-10-24T01:00:00Z")),
                refunded ? List.of() : List.of("RETRY", "MANUAL_COMPLETE"));
    }

    private static MockHttpServletRequestBuilder retry(UUID orderId, String note) {
        return post("/api/v1/admin/orders/{orderId}/refund/retry", orderId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedTransactionId\":\"%s\",\"note\":\"%s\"}".formatted(UUID.randomUUID(), note));
    }

    // ---- 확인 ----

    private static Map<String, Object> byId(List<Map<String, Object>> items, UUID orderId) {
        return items.stream().filter(item -> orderId.toString().equals(item.get("orderId"))).findFirst().orElseThrow();
    }

    private boolean refundFailedAtIsNull(Order order) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject("SELECT refund_failed_at IS NULL FROM orders WHERE id = ?",
                Boolean.class, bytes(order.id())));
    }

    private Map<String, Object> lastEvent(Order order) {
        return jdbcTemplate.queryForList("""
                SELECT from_status, to_status, actor, reason FROM order_events
                 WHERE order_id = ? ORDER BY event_sequence DESC LIMIT 1
                """, bytes(order.id())).getFirst();
    }
}
