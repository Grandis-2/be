package com.grandis.nova.order.order.api;

import com.grandis.nova.common.testing.Concurrently;
import com.grandis.nova.common.testing.Concurrently.Outcome;
import com.grandis.nova.order.order.OrderLedger;
import com.grandis.nova.order.order.cancel.CancelReason;
import com.grandis.nova.order.order.cancel.CancelSettlement;
import com.grandis.nova.order.order.cancel.SettlePreorderCancelCommand;
import com.grandis.nova.order.order.cancel.SettlePreorderCancelService;
import com.grandis.nova.order.order.domain.enums.OrderStatus;
import com.grandis.nova.order.order.domain.enums.OrderTrigger;
import com.grandis.nova.order.order.domain.model.Order;
import com.grandis.nova.order.order.vo.EventCause;
import com.grandis.nova.order.outbox.PreorderOrderSettled;
import com.grandis.nova.order.outbox.PreorderOrderSettled.RejectReason;
import com.grandis.nova.order.outbox.PreorderOrderSettled.Result;
import com.grandis.nova.order.support.OrderFixtures;
import com.grandis.nova.order.support.OrderIntegrationTest;
import com.grandis.nova.order.support.PlacedOrders;
import com.grandis.nova.order.support.TestAuth;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 관리자 배송 단계 전이. 요청 하나가 트랜잭션 하나로 커밋되므로 결과는 DB 와 조회 API 로 본다.
 * 권한 · 폐기 조회 실패는 진짜 토큰으로 SecurityRulesTest 가 본다.
 */
@OrderIntegrationTest
@AutoConfigureMockMvc
class AdminShippingApiTest {

    static final int REQUESTS = 5;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    OrderLedger ledger;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    SettlePreorderCancelService cancelSettlement;

    @Autowired
    JsonMapper jsonMapper;

    OrderFixtures fixtures;
    PlacedOrders orders;
    Long customerId;

    @BeforeEach
    void setUp() {
        fixtures = new OrderFixtures(jdbcTemplate);
        orders = new PlacedOrders(ledger, transactionTemplate, fixtures);
        customerId = fixtures.customer();
    }

    @Test
    void advancesPaidOrderStepByStepAndRecordsAdminReason() throws Exception {
        Order order = paidOrder();
        List<String[]> steps = List.of(
                new String[]{"PREPARATION_STARTED", "PREPARING_ITEMS", "준비 시작"},
                new String[]{"PACKED", "READY_TO_SHIP", "포장 완료"},
                new String[]{"SHIPPED", "SHIPPED", "출고"},
                new String[]{"DELIVERED", "DELIVERED", "배송 완료 확인"});

        for (String[] step : steps) {
            advance(order, step[0], step[2])
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.orderId").value(order.orderToken().value()))
                    .andExpect(jsonPath("$.data.status").value(step[1]))
                    .andExpect(jsonPath("$.data.applied").value(true));
        }

        assertThat(statusOf(order)).isEqualTo("DELIVERED");
        assertThat(jdbcTemplate.queryForList("""
                SELECT CONCAT(from_status, '>', to_status, '|', actor, '|', reason) FROM order_events
                 WHERE order_id = ? AND actor = 'ADMIN' ORDER BY event_sequence
                """, String.class, order.id())).containsExactly(
                "AWAITING_CONFIRMATION>PREPARING_ITEMS|ADMIN|준비 시작",
                "PREPARING_ITEMS>READY_TO_SHIP|ADMIN|포장 완료",
                "READY_TO_SHIP>SHIPPED|ADMIN|출고",
                "SHIPPED>DELIVERED|ADMIN|배송 완료 확인");
    }

    // 응답을 잃은 재요청 · 두 번 누름. 이력은 처음 것 하나다.
    @Test
    void repeatingTheCurrentStepIsOkWithoutChange() throws Exception {
        Order order = paidOrder();
        advance(order, "PREPARATION_STARTED", "첫 요청").andExpect(status().isOk());
        int events = eventCount(order);

        advance(order, "PREPARATION_STARTED", "다시 누름")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PREPARING_ITEMS"))
                .andExpect(jsonPath("$.data.applied").value(false));

        assertThat(eventCount(order)).isEqualTo(events);
    }

    @Test
    void stepFromAnotherStatusIsConflictWithCurrentStatus() throws Exception {
        Order skipping = paidOrder();
        Order unpaid = orders.place(customerId);
        Order authorizing = orders.place(customerId);
        transactionTemplate.executeWithoutResult(status ->
                ledger.requestPayment(authorizing.id(), "toss-" + authorizing.id(), EventCause.user()));
        Order refunding = paidOrder();
        transactionTemplate.executeWithoutResult(status -> ledger.fire(refunding.id(), OrderTrigger.CANCEL_REQUESTED,
                EnumSet.of(OrderStatus.AWAITING_CONFIRMATION), EventCause.user()));
        Order canceled = orders.place(customerId);
        orders.cancel(canceled.id(), "EXPIRY");
        Order passed = paidOrder();
        advance(passed, "PREPARATION_STARTED", "준비").andExpect(status().isOk());
        advance(passed, "PACKED", "포장").andExpect(status().isOk());

        Map<Order, String[]> cases = Map.of(
                skipping, new String[]{"SHIPPED", "AWAITING_CONFIRMATION"},
                unpaid, new String[]{"PREPARATION_STARTED", "AWAITING_PAYMENT"},
                authorizing, new String[]{"PREPARATION_STARTED", "AUTHORIZING"},
                refunding, new String[]{"PREPARATION_STARTED", "CANCELING"},
                canceled, new String[]{"PREPARATION_STARTED", "CANCELED"},
                passed, new String[]{"PREPARATION_STARTED", "READY_TO_SHIP"});
        for (Map.Entry<Order, String[]> c : cases.entrySet()) {
            Order order = c.getKey();
            int events = eventCount(order);

            advance(order, c.getValue()[0], "사유")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("ORDER_STEP_NOT_ALLOWED"))
                    .andExpect(jsonPath("$.error.details.status").value(c.getValue()[1]));

            assertThat(statusOf(order)).isEqualTo(c.getValue()[1]);
            assertThat(eventCount(order)).isEqualTo(events);
        }
    }

    @Test
    void reasonIsRequiredAndBounded() throws Exception {
        Order order = paidOrder();

        for (String body : List.of(
                "{\"step\":\"PREPARATION_STARTED\"}",
                "{\"step\":\"PREPARATION_STARTED\",\"reason\":\"   \"}",
                "{\"step\":\"PREPARATION_STARTED\",\"reason\":\"%s\"}".formatted("가".repeat(501)))) {
            send(order, body)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.error.details.violations[0].field").value("reason"));
        }
        assertThat(statusOf(order)).isEqualTo("AWAITING_CONFIRMATION");
    }

    // 500자는 코드 포인트로 센다(DB utf8mb4 와 같다). 이모지 500개는 UTF-16 으로 1000 이지만 받는다.
    @Test
    void reasonLengthCountsCodePoints() throws Exception {
        Order order = paidOrder();

        advance(order, "PREPARATION_STARTED", "📦".repeat(500)).andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT CHAR_LENGTH(reason) FROM order_events WHERE order_id = ? AND actor = 'ADMIN'",
                Integer.class, order.id())).isEqualTo(500);
    }

    // 취소 · 환불 완료처럼 배송이 아닌 사건은 단계로 받지 않는다.
    @Test
    void unknownOrNonShippingStepIsBadRequest() throws Exception {
        Order order = paidOrder();

        for (String step : List.of("CANCEL_REQUESTED", "REFUND_COMPLETED", "PAYMENT_APPROVED", "PREPARING_ITEMS")) {
            advance(order, step, "사유").andExpect(status().isBadRequest());
        }
        send(order, "{\"reason\":\"사유\"}").andExpect(status().isBadRequest());
        assertThat(statusOf(order)).isEqualTo("AWAITING_CONFIRMATION");
    }

    @Test
    void unknownOrMalformedOrderIsNotFound() throws Exception {
        for (String orderId : List.of("3f2504e0-4f89-11d3-9a0c-0305e82c3301", "not-a-token")) {
            mockMvc.perform(post("/api/v1/admin/orders/{orderId}/shipping-steps", orderId).with(admin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"step\":\"PREPARATION_STARTED\",\"reason\":\"사유\"}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
        }
    }

    @Test
    void customerTokenIsForbidden() throws Exception {
        Order order = paidOrder();

        mockMvc.perform(post("/api/v1/admin/orders/{orderId}/shipping-steps", order.orderToken().value())
                        .with(TestAuth.customer(customerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"step\":\"PREPARATION_STARTED\",\"reason\":\"사유\"}"))
                .andExpect(status().isForbidden());
        assertThat(statusOf(order)).isEqualTo("AWAITING_CONFIRMATION");
    }

    // 관리자 사유는 내부 기록이다 — 사용자 이력에는 누가 · 무엇을 했는지만 나간다(NV-45 이월 24번).
    @Test
    void customerHistoryHidesAdminReasonButAdminDetailShowsIt() throws Exception {
        Order order = paidOrder();
        advance(order, "PREPARATION_STARTED", "주소 재확인 필요 — 고객 통화 예정").andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/orders/{orderId}", order.orderToken().value())
                        .with(TestAuth.customer(customerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.events", hasSize(4)))
                .andExpect(jsonPath("$.data.events[2].reason").value("PAYMENT_APPROVED"))
                .andExpect(jsonPath("$.data.events[3].actor").value("ADMIN"))
                .andExpect(jsonPath("$.data.events[3].toStatus").value("PREPARING_ITEMS"))
                .andExpect(jsonPath("$.data.events[3].reason").isEmpty());
        mockMvc.perform(get("/api/v1/admin/orders/{orderId}", order.orderToken().value()).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.events[3].reason").value("주소 재확인 필요 — 고객 통화 예정"));
    }

    @Test
    void concurrentSameStepAppliesOnce() throws Exception {
        Order order = paidOrder();
        int events = eventCount(order);

        List<Outcome<Boolean>> outcomes = Concurrently.run(REQUESTS, i -> () -> {
            String body = advance(order, "PREPARATION_STARTED", "동시 " + i)
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            return JsonPath.<Boolean>read(body, "$.data.applied");
        });

        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome.error()).isNull());
        assertThat(outcomes.stream().map(Outcome::value).toList())
                .containsOnlyOnce(true)
                .containsOnly(true, false);
        assertThat(statusOf(order)).isEqualTo("PREPARING_ITEMS");
        assertThat(eventCount(order)).isEqualTo(events + 1);
    }

    // 관리자 출고가 예약 취소 거절(settledFor 의 SHIPPED 줄)로 이어지는지 실제 경로로 본다.
    @Test
    void preorderCancelAfterShipmentIsRejectedAsShipped() throws Exception {
        Order order = paidOrder();
        advance(order, "PREPARATION_STARTED", "준비").andExpect(status().isOk());
        advance(order, "PACKED", "포장").andExpect(status().isOk());
        advance(order, "SHIPPED", "출고").andExpect(status().isOk());

        CancelSettlement settlement = cancelSettlement.settle(new SettlePreorderCancelCommand(order.preorderId(),
                order.preorderToken(), customerId, CancelReason.USER, 1L));

        assertThat(settlement).isInstanceOf(CancelSettlement.Settled.class);
        PreorderOrderSettled settled = ((CancelSettlement.Settled) settlement).result();
        assertThat(settled.result()).isEqualTo(Result.REJECTED);
        assertThat(settled.reason()).isEqualTo(RejectReason.SHIPPED);
        assertThat(statusOf(order)).isEqualTo("SHIPPED");
    }

    // D20: 결제된 주문의 예약 취소가 먼저 오면 취소 중으로 잠근다 — 그 뒤의 관리자 배송 전이는 409 다
    @Test
    void preorderCancelOfPaidOrderLocksShippingSteps() throws Exception {
        Order order = paidOrder();

        CancelSettlement settlement = cancelSettlement.settle(new SettlePreorderCancelCommand(order.preorderId(),
                order.preorderToken(), customerId, CancelReason.USER, 1L));

        assertThat(settlement).isEqualTo(new CancelSettlement.Deferred(OrderStatus.CANCELING));
        advance(order, "PREPARATION_STARTED", "준비")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.details.status").value("CANCELING"));
        assertThat(statusOf(order)).isEqualTo("CANCELING");
    }

    // D20: 승인 중이라 보류된 예약 취소가 다시 오기 전에 관리자가 출고하면, 다시 받은 취소는 REJECTED(SHIPPED) 다
    @Test
    void cancelDeferredWhileAuthorizingThenShippedIsRejectedAsShipped() throws Exception {
        Order order = orders.place(customerId);
        transactionTemplate.executeWithoutResult(status ->
                ledger.requestPayment(order.id(), "toss-" + order.id(), EventCause.user()));
        SettlePreorderCancelCommand cancel = new SettlePreorderCancelCommand(order.preorderId(), order.preorderToken(),
                customerId, CancelReason.USER, 1L);

        assertThat(cancelSettlement.settle(cancel)).isEqualTo(new CancelSettlement.Deferred(OrderStatus.AUTHORIZING));
        transactionTemplate.executeWithoutResult(status -> ledger.settlePayment(order.id(),
                OrderTrigger.PAYMENT_APPROVED, null, EventCause.system("PAYMENT_APPROVED")));
        advance(order, "PREPARATION_STARTED", "준비").andExpect(status().isOk());
        advance(order, "PACKED", "포장").andExpect(status().isOk());
        advance(order, "SHIPPED", "출고").andExpect(status().isOk());

        CancelSettlement again = cancelSettlement.settle(cancel);

        assertThat(again).isInstanceOf(CancelSettlement.Settled.class);
        PreorderOrderSettled settled = ((CancelSettlement.Settled) again).result();
        assertThat(settled.result()).isEqualTo(Result.REJECTED);
        assertThat(settled.reason()).isEqualTo(RejectReason.SHIPPED);
        assertThat(statusOf(order)).isEqualTo("SHIPPED");
    }

    private Order paidOrder() {
        Order order = orders.place(customerId);
        orders.pay(order.id());
        return order;
    }

    private ResultActions advance(Order order, String step, String reason) throws Exception {
        return send(order, jsonMapper.writeValueAsString(Map.of("step", step, "reason", reason)));
    }

    private ResultActions send(Order order, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/admin/orders/{orderId}/shipping-steps", order.orderToken().value())
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private String statusOf(Order order) {
        return jdbcTemplate.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, order.id());
    }

    private int eventCount(Order order) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM order_events WHERE order_id = ?", Integer.class,
                order.id());
    }

    private static RequestPostProcessor admin() {
        return TestAuth.admin();
    }
}
