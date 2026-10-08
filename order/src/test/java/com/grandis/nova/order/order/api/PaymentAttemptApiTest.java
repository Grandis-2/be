package com.grandis.nova.order.order.api;

import com.grandis.nova.common.security.BearerTokens;
import com.grandis.nova.common.web.ApiResponse;
import com.grandis.nova.order.client.payment.CaptureRequest;
import com.grandis.nova.order.client.payment.PaymentAttempt;
import com.grandis.nova.order.client.payment.PaymentClient;
import com.grandis.nova.order.client.preorder.PreorderClient;
import com.grandis.nova.order.client.preorder.PreorderPayability;
import com.grandis.nova.order.order.OrderLedger;
import com.grandis.nova.order.order.domain.model.Order;
import com.grandis.nova.order.order.vo.EventCause;
import com.grandis.nova.order.support.OrderFixtures;
import com.grandis.nova.order.support.OrderFixtures.PreorderProduct;
import com.grandis.nova.order.support.OrderIntegrationTest;
import com.grandis.nova.order.support.PreorderStubs;
import com.grandis.nova.order.support.TestAuth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.ResourceAccessException;

import java.math.BigDecimal;
import java.util.UUID;

import static com.grandis.nova.order.support.OrderFixtures.bytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /api/v1/orders/{orderToken}/payment-attempts — 결제창을 열 값을 돌려준다. MySQL 위에서 돌고 preorder · payment 내부 API 만 대역이다.
 * 대역 응답 모양의 계약 대조는 PreorderClientTest · PaymentPreparerTest 가 한다.
 *
 * 인증은 흉내({@link TestAuth})다 — 필터가 토큰을 읽지 않으므로 Authorization 헤더의 값은 전달 확인용 자리일 뿐이다.
 */
@OrderIntegrationTest
@AutoConfigureMockMvc
class PaymentAttemptApiTest {

    // 사용자가 보낸 액세스 토큰 자리(Authorization: Bearer 의 값). preorder · payment 에 그대로 전달돼야 한다. 실제 토큰 모양이 아니다.
    static final String SESSION = "payment-attempt-api-test";
    static final String PROVIDER_ORDER_ID = "6f1c2d3e-4b5a-4c7d-8e9f-0a1b2c3d4e5f";
    // 저장된 총액. 수량 1 이라 원래 단가와 같으므로 테스트에서 단가와 다르게 바꿔 둔다 — 단가를 보내는 실수를 잡으려고.
    static final BigDecimal TOTAL = new BigDecimal("1300000");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    OrderLedger ledger;

    @Autowired
    PlatformTransactionManager transactionManager;

    @MockitoBean
    PreorderClient preorderClient;

    @MockitoBean
    PaymentClient paymentClient;

    OrderFixtures fixtures;
    PreorderProduct product;
    UUID customerId;
    UUID preorderId;
    Order order;

    @BeforeEach
    void setUp() {
        fixtures = new OrderFixtures(jdbcTemplate);
        product = fixtures.preorderProduct();
        customerId = fixtures.customer();
        preorderId = fixtures.payablePreorder(customerId, product, 1);
        order = new TransactionTemplate(transactionManager).execute(status -> ledger.place(
                OrderFixtures.preorderCommand(customerId, preorderId, product).toDraft(), EventCause.user()));
    }

    @Test
    void returnsValuesToOpenPaymentWindow() throws Exception {
        jdbcTemplate.update("UPDATE orders SET total_amount = ? WHERE id = ?", TOTAL, bytes(order.id()));
        stubPreorder(PreorderStubs.payable(preorderId, order.preorderToken(), customerId, product));
        stubPaymentOpens(TOTAL);

        prepare(customerId, order.orderToken().value())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.tossOrderId").value(PROVIDER_ORDER_ID))
                .andExpect(jsonPath("$.data.amount").value(1300000))
                .andExpect(jsonPath("$.data.orderName").value(OrderFixtures.PRODUCT_TITLE + " " + OrderFixtures.OPTION_TITLE))
                .andExpect(jsonPath("$.data.customerKey").doesNotExist());

        // 예약은 주문에 저장한 UUID 로 묻고, 결제는 주문 id · 서버 금액으로 연다. 사용자 토큰은 둘 다에 그대로 싣는다.
        verify(preorderClient).getPayability(order.preorderToken(), BearerTokens.value(SESSION));
        verify(paymentClient).openCapture(new CaptureRequest("ORDER", order.id(), TOTAL), BearerTokens.value(SESSION));
        assertThat(statusOf(order)).isEqualTo("AWAITING_PAYMENT");
    }

    // ── 주문 확인: 없거나 남의 주문은 존재를 숨긴다 ───────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"00000000-0000-4000-8000-000000000000", "not-a-token", "6F1C2D3E-4B5A-4C7D-8E9F-0A1B2C3D4E5F"})
    void unknownOrderIsNotFound(String orderToken) throws Exception {
        prepare(customerId, orderToken)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));

        verifyNoInteractions(preorderClient, paymentClient);
    }

    @Test
    void someoneElsesOrderIsNotFound() throws Exception {
        prepare(fixtures.customer(), order.orderToken().value())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));

        verifyNoInteractions(preorderClient, paymentClient);
    }

    // 결제 대기가 아닌 주문(결제 확인 중 · 결제됨 · 취소됨 등)은 다시 결제창을 열지 않는다. preorder 에 묻지도 않는다.
    @ParameterizedTest
    @ValueSource(strings = {"AUTHORIZING", "AWAITING_CONFIRMATION", "CANCELING", "CANCELED"})
    void orderNotAwaitingPaymentIsNotPayable(String status) throws Exception {
        fixtures.forceStatus(order.id(), status);

        prepare(customerId, order.orderToken().value())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_PAYABLE"));

        verifyNoInteractions(preorderClient, paymentClient);
    }

    // 0원 주문은 결제창을 열 수 없다. payment 에 보내면 400 → 늘 500 이 되므로 부르기 전에 거절한다.
    @Test
    void zeroAmountOrderIsNotPayable() throws Exception {
        jdbcTemplate.update("UPDATE orders SET total_amount = 0 WHERE id = ?", bytes(order.id()));
        stubPreorder(PreorderStubs.payable(preorderId, order.preorderToken(), customerId, product));

        prepare(customerId, order.orderToken().value())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_PAYABLE"));

        verifyNoInteractions(paymentClient);
    }

    // 0원 거절은 예약 확인 뒤다 — 예약이 취소된 0원 주문도 남은 주문 정리(R1)를 거친다.
    @Test
    void zeroAmountOrderOfCanceledPreorderIsStillCanceled() throws Exception {
        jdbcTemplate.update("UPDATE orders SET total_amount = 0 WHERE id = ?", bytes(order.id()));
        stubPreorder(PreorderStubs.blocked(preorderId, order.preorderToken(), customerId, product, "CANCELED"));

        prepare(customerId, order.orderToken().value())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PREORDER_NOT_PAYABLE"));

        assertThat(statusOf(order)).isEqualTo("CANCELED");
    }

    // ── 결제 가능 재확인(preorder) ─────────────────────────────────────────────

    /*
     * 주문 생성과 예약 취소가 엇갈리면(주문이 없을 때 취소가 정리돼 NO_ORDER) 예약은 취소됐는데 미결제 주문이 남는다(R1).
     * 결제 시도에서 그 주문을 취소하고 거절한다. 예약은 이미 취소돼 있어 preorder 에 따로 알리지 않는다.
     */
    @Test
    void canceledPreorderCancelsLeftoverOrder() throws Exception {
        stubPreorder(PreorderStubs.blocked(preorderId, order.preorderToken(), customerId, product, "CANCELED"));

        prepare(customerId, order.orderToken().value())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PREORDER_NOT_PAYABLE"));

        assertThat(statusOf(order)).isEqualTo("CANCELED");
        assertThat(jdbcTemplate.queryForMap("""
                SELECT from_status, to_status, actor, reason FROM order_events WHERE order_id = ? AND event_sequence = 2
                """, bytes(order.id())))
                .containsEntry("from_status", "AWAITING_PAYMENT")
                .containsEntry("to_status", "CANCELED")
                .containsEntry("actor", "SYSTEM")
                .containsEntry("reason", "PREORDER_CANCELED");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM order_outbox_events WHERE aggregate_id = ?",
                Integer.class, bytes(preorderId))).isZero();
        verifyNoInteractions(paymentClient);
    }

    // 주문을 읽은 뒤 · 취소 전에 승인이 시작됐으면(AUTHORIZING) 원장이 잠근 뒤 전제로 거른다 — 바꾸지 않고 거절만 한다.
    @Test
    void leftoverCancelSkipsOrderThatMovedOn() throws Exception {
        PreorderPayability canceled =
                PreorderStubs.blocked(preorderId, order.preorderToken(), customerId, product, "CANCELED");
        given(preorderClient.getPayability(eq(order.preorderToken()), any())).willAnswer(invocation -> {
            fixtures.forceStatus(order.id(), "AUTHORIZING");
            return ApiResponse.ok(canceled);
        });

        prepare(customerId, order.orderToken().value())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PREORDER_NOT_PAYABLE"));

        assertThat(statusOf(order)).isEqualTo("AUTHORIZING");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM order_events WHERE order_id = ?",
                Integer.class, bytes(order.id()))).isEqualTo(1);
        verifyNoInteractions(paymentClient);
    }

    // 취소 중인 예약은 취소 요청 메시지가 주문을 정리한다 — 여기서 먼저 취소하지 않는다.
    @Test
    void cancelingPreorderLeavesOrderToCancelMessage() throws Exception {
        stubPreorder(PreorderStubs.blocked(preorderId, order.preorderToken(), customerId, product, "CANCELING"));

        prepare(customerId, order.orderToken().value())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PREORDER_NOT_PAYABLE"));

        assertThat(statusOf(order)).isEqualTo("AWAITING_PAYMENT");
        verifyNoInteractions(paymentClient);
    }

    @Test
    void duePassedIsPaymentWindowExpired() throws Exception {
        stubPreorder(PreorderStubs.blocked(preorderId, order.preorderToken(), customerId, product, "DUE_PASSED"));

        prepare(customerId, order.orderToken().value())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PAYMENT_WINDOW_EXPIRED"));

        assertThat(statusOf(order)).isEqualTo("AWAITING_PAYMENT");
        verifyNoInteractions(paymentClient);
    }

    @Test
    void otherBlockedPreorderIsNotPayable() throws Exception {
        stubPreorder(PreorderStubs.blocked(preorderId, order.preorderToken(), customerId, product, "NOT_YET_REGISTERED"));

        prepare(customerId, order.orderToken().value())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PREORDER_NOT_PAYABLE"));

        verifyNoInteractions(paymentClient);
    }

    // 주문은 그 예약에서 만들었다 — 예약이 안 보이면(없음 · 남의 것) 데이터가 어긋난 것이라 500 이다. 결제창을 열지 않는다.
    @Test
    void missingPreorderIsInternalError() throws Exception {
        PreorderStubs.stubPreorderNotFound(preorderClient, order.preorderToken());

        prepare(customerId, order.orderToken().value())
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"));

        verifyNoInteractions(paymentClient);
    }

    @Test
    void responseForAnotherPreorderIdIsInternalError() throws Exception {
        stubPreorder(PreorderStubs.payable(UUID.randomUUID(), order.preorderToken(), customerId, product));

        prepare(customerId, order.orderToken().value()).andExpect(status().isInternalServerError());

        verifyNoInteractions(paymentClient);
    }

    @Test
    void preorderOfAnotherCustomerIsInternalError() throws Exception {
        stubPreorder(PreorderStubs.payable(preorderId, order.preorderToken(), fixtures.customer(), product));

        prepare(customerId, order.orderToken().value()).andExpect(status().isInternalServerError());

        verifyNoInteractions(paymentClient);
    }

    @Test
    void preorderRejectingTokenIsUnauthenticated() throws Exception {
        PreorderStubs.stubClientError(preorderClient, order.preorderToken(), HttpStatus.UNAUTHORIZED);

        prepare(customerId, order.orderToken().value())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    // ── payment 호출 ───────────────────────────────────────────────────────

    @Test
    void paymentUnavailableIsServiceUnavailable() throws Exception {
        stubPreorder(PreorderStubs.payable(preorderId, order.preorderToken(), customerId, product));
        given(paymentClient.openCapture(any(), any())).willThrow(new ResourceAccessException("Read timed out"));

        prepare(customerId, order.orderToken().value())
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("DEPENDENCY_UNAVAILABLE"));

        assertThat(statusOf(order)).isEqualTo("AWAITING_PAYMENT");
    }

    private void stubPreorder(PreorderPayability payability) {
        PreorderStubs.stub(preorderClient, payability);
    }

    private void stubPaymentOpens(BigDecimal amount) {
        given(paymentClient.openCapture(any(), eq(BearerTokens.value(SESSION))))
                .willReturn(ApiResponse.ok(new PaymentAttempt(PROVIDER_ORDER_ID, amount)));
    }

    private ResultActions prepare(UUID customer, String orderToken) throws Exception {
        return mockMvc.perform(post("/api/v1/orders/{orderToken}/payment-attempts", orderToken)
                .with(TestAuth.customer(customer))
                .header(BearerTokens.HEADER, BearerTokens.value(SESSION)));
    }

    private String statusOf(Order placed) {
        return jdbcTemplate.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, bytes(placed.id()));
    }
}
