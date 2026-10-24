package com.grandis.nova.order.order.api;

import com.grandis.nova.common.outbox.OutboxWriter;
import com.grandis.nova.common.security.BearerTokens;
import com.grandis.nova.common.web.ApiResponse;
import com.grandis.nova.order.client.payment.ConfirmReply;
import com.grandis.nova.order.client.payment.ConfirmRequest;
import com.grandis.nova.order.client.payment.DeclineReason;
import com.grandis.nova.order.client.payment.PaymentConfirmClient;
import com.grandis.nova.order.client.preorder.PreorderClient;
import com.grandis.nova.order.order.OrderLedger;
import com.grandis.nova.order.order.domain.model.Order;
import com.grandis.nova.order.order.vo.EventCause;
import com.grandis.nova.order.outbox.OrderPaymentAuthorizing;
import com.grandis.nova.order.support.OrderFixtures;
import com.grandis.nova.order.support.OrderFixtures.PreorderProduct;
import com.grandis.nova.order.support.OrderIntegrationTest;
import com.grandis.nova.order.support.PreorderStubs;
import com.grandis.nova.order.support.TestAuth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.ResourceAccessException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.net.ConnectException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.grandis.nova.order.support.OrderFixtures.bytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 승인 중 알림(ORDER_PAYMENT_AUTHORIZING). 주문을 승인 중으로 바꾼 트랜잭션에 함께 쌓이고, 그 트랜잭션이 되돌아가면 없다 — payment 는 이
 * 알림으로만 승인 요청이 닿지 않은 결제창을 넘기므로, 승인 중이 아닌 주문의 결제창이 승인되지 않는 근거다. preorder · payment 내부 API 만 대역이다.
 */
@OrderIntegrationTest
@AutoConfigureMockMvc
class OrderPaymentAuthorizingTest {

    static final String SESSION = "order-payment-authorizing-test";
    // 결제창이 돌려준 결제 키 자리. 알림에 실리지 않는지 본다. 실제 키 모양이 아니다.
    static final String PAYMENT = "tgen_authorizing_20261023";
    static final String AUTHORIZING = "ORDER_PAYMENT_AUTHORIZING";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    JsonMapper jsonMapper;

    @Autowired
    OrderLedger ledger;

    @Autowired
    PlatformTransactionManager transactionManager;

    @MockitoBean
    PreorderClient preorderClient;

    @MockitoBean
    PaymentConfirmClient paymentClient;

    @MockitoSpyBean
    OutboxWriter outboxWriter;

    Order order;
    UUID customerId;
    BigDecimal total;
    String attempt;

    @BeforeEach
    void setUp() {
        OrderFixtures fixtures = new OrderFixtures(jdbcTemplate);
        PreorderProduct product = fixtures.preorderProduct();
        customerId = fixtures.customer();
        UUID preorderId = OrderFixtures.preorderId();
        order = new TransactionTemplate(transactionManager).execute(status -> ledger.place(
                OrderFixtures.preorderCommand(customerId, preorderId, product).toDraft(), EventCause.user()));
        total = order.totalAmount().amount();
        attempt = UUID.randomUUID().toString();
        PreorderStubs.stub(preorderClient, PreorderStubs.payable(preorderId, order.preorderToken(), customerId, product));
    }

    // 승인 요청이 payment 에 닿지 않았다(연결 실패) — 주문은 승인 중이고, 알림이 함께 커밋돼 payment 가 넘길 수 있다
    @Test
    void authorizingTransitionCarriesNoticeInSameTransaction() throws Exception {
        given(paymentClient.confirm(any(), any(), any())).willAnswer(call -> {
            if (isCheck(call)) {
                return reply(ConfirmReply.Result.PENDING);
            }
            throw new ResourceAccessException("Connection refused", new ConnectException("Connection refused"));
        });

        confirm();

        assertThat(orderStatus()).isEqualTo("AUTHORIZING");
        List<Map<String, Object>> notices = notices();
        assertThat(notices).singleElement().satisfies(notice -> {
            assertThat(notice).containsEntry("aggregate_type", "ORDER")
                    .containsEntry("aggregate_id", order.id().toString());
            JsonNode payload = jsonMapper.readTree((String) notice.get("payload"));
            assertThat(payload.propertyNames()).containsExactlyInAnyOrder("providerOrderId", "amount");
            assertThat(payload.get("providerOrderId").asString()).isEqualTo(attempt);
            assertThat(payload.get("amount").decimalValue()).isEqualByComparingTo(total);
            assertThat((String) notice.get("payload")).doesNotContain(PAYMENT);
        });
    }

    // 승인 중 전환이 되돌아가면 알림도 없다 — 알림을 적은 뒤 트랜잭션이 실패해도 둘 다 남지 않는다.
    // 스파이는 트랜잭션 프록시 안쪽에 있어 그 대상에 스텁한다(프록시에 걸면 스텁하는 호출이 MANDATORY 에 걸린다)
    @Test
    void rolledBackTransitionLeavesNoNotice() throws Exception {
        given(paymentClient.confirm(any(), any(), any())).willReturn(reply(ConfirmReply.Result.PENDING));
        willAnswer(call -> {
            Object appended = call.callRealMethod();
            if (call.getArgument(0) instanceof OrderPaymentAuthorizing) {
                throw new IllegalStateException("알림을 적은 뒤 트랜잭션이 실패했다");
            }
            return appended;
        }).given(AopTestUtils.<OutboxWriter>getUltimateTargetObject(outboxWriter)).append(any());

        confirm().andExpect(status().is5xxServerError());

        assertThat(orderStatus()).isEqualTo("AWAITING_PAYMENT");
        assertThat(notices()).isEmpty();
        verify(paymentClient, never()).confirm(any(), argThat(ConfirmRequest::startAllowed), any());
    }

    // 승인 중 재요청은 전환하지 않으므로 알림을 더 적지 않는다
    @Test
    void repeatedConfirmWhileAuthorizingAddsNoNotice() throws Exception {
        given(paymentClient.confirm(any(), any(), any())).willReturn(reply(ConfirmReply.Result.PENDING));

        confirm().andExpect(jsonPath("$.data.result").value("PENDING"));
        confirm().andExpect(jsonPath("$.data.result").value("PENDING"));

        assertThat(orderStatus()).isEqualTo("AUTHORIZING");
        assertThat(notices()).hasSize(1);
    }

    // 결제창 확인이 끝난 결제창(거절 · 만료)을 보이면 승인 중으로 바꾸지 않는다 — 알림도 없다
    @Test
    void declinedCheckLeavesNoNotice() throws Exception {
        given(paymentClient.confirm(any(), any(), any())).willReturn(ApiResponse.ok(
                new ConfirmReply(ConfirmReply.Result.DECLINED, DeclineReason.PAYMENT_EXPIRED)));

        confirm().andExpect(jsonPath("$.data.result").value("DECLINED"));

        assertThat(orderStatus()).isEqualTo("AWAITING_PAYMENT");
        assertThat(notices()).isEmpty();
    }

    private ResultActions confirm() throws Exception {
        return mockMvc.perform(post("/api/v1/orders/{orderId}/payment-attempts/{tossOrderId}/confirm",
                order.id().toString(), attempt)
                .with(TestAuth.customer(customerId))
                .header(BearerTokens.HEADER, BearerTokens.value(SESSION))
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(Map.of("paymentKey", PAYMENT, "amount", total))));
    }

    /** request 가 null 이면 다시 스텁하는 중이다. */
    private static boolean isCheck(InvocationOnMock call) {
        ConfirmRequest request = call.getArgument(1);
        return request != null && request.reserve();
    }

    private static ApiResponse<ConfirmReply> reply(ConfirmReply.Result result) {
        return ApiResponse.ok(new ConfirmReply(result, null));
    }

    private String orderStatus() {
        return jdbcTemplate.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, bytes(order.id()));
    }

    private List<Map<String, Object>> notices() {
        return jdbcTemplate.queryForList("""
                SELECT aggregate_type, BIN_TO_UUID(aggregate_id) AS aggregate_id, payload FROM order_outbox_events
                 WHERE aggregate_type = 'ORDER' AND aggregate_id = ? AND event_type = ?
                """, bytes(order.id()), AUTHORIZING);
    }
}
