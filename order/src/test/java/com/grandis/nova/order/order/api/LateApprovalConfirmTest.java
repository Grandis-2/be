package com.grandis.nova.order.order.api;

import com.grandis.nova.common.security.BearerTokens;
import com.grandis.nova.common.web.ApiResponse;
import com.grandis.nova.order.client.payment.ConfirmReply;
import com.grandis.nova.order.client.payment.ConfirmRequest;
import com.grandis.nova.order.client.payment.DeclineReason;
import com.grandis.nova.order.client.payment.PaymentConfirmClient;
import com.grandis.nova.order.client.preorder.PreorderClient;
import com.grandis.nova.order.order.OrderLedger;
import com.grandis.nova.order.order.cancel.UnconfirmedCartOrderRelease;
import com.grandis.nova.order.order.command.PlaceOrderCommand;
import com.grandis.nova.order.order.domain.enums.OrderSource;
import com.grandis.nova.order.order.domain.model.Order;
import com.grandis.nova.order.order.vo.EventCause;
import com.grandis.nova.order.stock.StockLedger;
import com.grandis.nova.order.support.OrderFixtures;
import com.grandis.nova.order.support.OrderIntegrationTest;
import com.grandis.nova.order.support.TestAuth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.grandis.nova.order.support.OrderFixtures.bytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 승인 응답을 기다리는 사이 결과를 모른 주문 취소(만든 뒤 20분)가 먼저 끝난 장바구니 주문. 승인이면 응답 경로가 자동 환불로 넘기고, 승인이든
 * 거절이든 프론트에는 결제 완료 · 거절(APPROVED · DECLINED)이 아니라 409 ORDER_NOT_PAYABLE 로 답한다. preorder · payment 내부 API 만 대역이다.
 */
@OrderIntegrationTest
@AutoConfigureMockMvc
class LateApprovalConfirmTest {

    static final String SESSION = "late-approval-confirm-test";
    // 결제창이 돌려준 결제 키 자리. 실제 키 모양이 아니다.
    static final String PAYMENT = "tgen_late_approval_20261024";
    static final BigDecimal PRICE = new BigDecimal("1250000");

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired JsonMapper jsonMapper;
    @Autowired OrderLedger ledger;
    @Autowired StockLedger stock;
    @Autowired UnconfirmedCartOrderRelease release;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoBean PreorderClient preorderClient;
    @MockitoBean PaymentConfirmClient paymentClient;

    UUID customerId;
    UUID phone;
    Order order;
    String attempt;

    @BeforeEach
    void setUp() {
        OrderFixtures fixtures = new OrderFixtures(jdbcTemplate);
        customerId = fixtures.customer();
        OrderFixtures.StockProduct product = fixtures.inStockProduct(1);
        phone = product.optionIds().getFirst();
        fixtures.stock(phone, 10, 0, 0);
        PlaceOrderCommand command = new PlaceOrderCommand(customerId, OrderSource.CART, null, null, null, OrderFixtures.ADDRESS,
                List.of(new PlaceOrderCommand.Line(product.productId(), phone, 1, PRICE, 0, BigDecimal.ZERO, "아이폰 17", null, "블랙", null)));
        order = new TransactionTemplate(transactionManager).execute(status -> {
            stock.reserve(Map.of(phone, 1));
            return ledger.place(command.toDraft(), EventCause.user());
        });
        attempt = UUID.randomUUID().toString();
    }

    @Test
    void 기다리는_사이_취소된_주문의_승인은_자동_환불을_적고_409_로_답한다() throws Exception {
        answerAfterCancel(new ConfirmReply(ConfirmReply.Result.APPROVED, null));

        confirmIsNotPayable();

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, bytes(order.id())))
                .isEqualTo("CANCELED");
        assertThat(jdbcTemplate.queryForObject("SELECT auto_refund_requested_at IS NOT NULL FROM orders WHERE id = ?",
                Boolean.class, bytes(order.id()))).isTrue();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM order_outbox_events
                 WHERE aggregate_type = 'ORDER' AND aggregate_id = ? AND event_type = 'ORDER_REFUND_REQUESTED'
                """, Long.class, bytes(order.id()))).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT stock_reserved FROM option_inventories WHERE option_id = ?",
                Integer.class, bytes(phone))).isZero();
    }

    @Test
    void 기다리는_사이_취소된_주문의_거절도_409_로_답한다() throws Exception {
        answerAfterCancel(new ConfirmReply(ConfirmReply.Result.DECLINED, DeclineReason.CARD_REJECTED));

        confirmIsNotPayable();

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, bytes(order.id())))
                .isEqualTo("CANCELED");
        assertThat(jdbcTemplate.queryForObject("SELECT auto_refund_requested_at IS NULL FROM orders WHERE id = ?",
                Boolean.class, bytes(order.id()))).isTrue();
    }

    /** 결제창 확인은 시작 전으로 답하고, 승인 호출은 그 사이 20분 취소가 먼저 커밋된 뒤 reply 로 답한다. */
    private void answerAfterCancel(ConfirmReply reply) {
        given(paymentClient.confirm(any(), any(), any())).willAnswer(call -> {
            ConfirmRequest request = call.getArgument(1);
            if (request.reserve()) {
                return ApiResponse.ok(new ConfirmReply(ConfirmReply.Result.PENDING, null));
            }
            new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                    release.cancel(order.id(), EventCause.system("PAYMENT_UNCONFIRMED")));
            return ApiResponse.ok(reply);
        });
    }

    private void confirmIsNotPayable() throws Exception {
        mockMvc.perform(post("/api/v1/orders/{orderId}/payment-attempts/{tossOrderId}/confirm", order.id().toString(), attempt)
                        .with(TestAuth.customer(customerId))
                        .header(BearerTokens.HEADER, BearerTokens.value(SESSION))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("paymentKey", PAYMENT, "amount", PRICE))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_PAYABLE"));
    }
}
