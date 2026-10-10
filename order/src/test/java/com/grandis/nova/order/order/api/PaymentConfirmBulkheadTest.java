package com.grandis.nova.order.order.api;

import com.grandis.nova.common.security.BearerTokens;
import com.grandis.nova.common.web.ApiResponse;
import com.grandis.nova.order.client.payment.ConfirmReply;
import com.grandis.nova.order.client.payment.PaymentConfirmClient;
import com.grandis.nova.order.client.preorder.PreorderClient;
import com.grandis.nova.order.order.OrderLedger;
import com.grandis.nova.order.order.domain.model.Order;
import com.grandis.nova.order.order.vo.EventCause;
import com.grandis.nova.order.support.OrderFixtures;
import com.grandis.nova.order.support.OrderFixtures.PreorderProduct;
import com.grandis.nova.order.support.OrderIntegrationTest;
import com.grandis.nova.order.support.PreorderStubs;
import com.grandis.nova.order.support.TestAuth;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.ResourceAccessException;

import java.net.ConnectException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.grandis.nova.order.support.OrderFixtures.bytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 결제 승인 동시 상한(격벽). payment 응답을 기다리는 승인이 상한만큼 차 있으면 다음 승인은 상태를 바꾸기 전에 503 이다 —
 * 토스가 느려져도 결제와 무관한 주문 API 의 요청 스레드가 남는다. 상한을 1 로 두고 본다.
 */
@OrderIntegrationTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "nova.payment-confirm.max-concurrency=1")
class PaymentConfirmBulkheadTest {

    static final String SESSION = "payment-confirm-bulkhead-test";
    static final String PAYMENT = "tgen_bulkhead_20261003";

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
    PaymentConfirmClient paymentClient;

    @Test
    void confirmBeyondLimitIsRejectedBeforeChangingOrder() throws Exception {
        OrderFixtures fixtures = new OrderFixtures(jdbcTemplate);
        PreorderProduct product = fixtures.preorderProduct();
        Order waiting = placedOrder(fixtures, product, 1);
        Order rejected = placedOrder(fixtures, product, 2);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        given(paymentClient.confirm(any(), any(), any())).willAnswer(invocation -> {
            entered.countDown();
            release.await(10, TimeUnit.SECONDS);
            return ApiResponse.ok(new ConfirmReply(ConfirmReply.Result.PENDING, null));
        });

        CompletableFuture<Void> first = CompletableFuture.runAsync(() -> {
            try {
                confirm(waiting).andExpect(status().isOk());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();

        confirm(rejected)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("DEPENDENCY_UNAVAILABLE"));

        assertThat(statusOf(rejected)).isEqualTo("AWAITING_PAYMENT");
        verify(preorderClient, never()).getPayability(eq(rejected.preorderToken()), any());
        release.countDown();
        first.get(10, TimeUnit.SECONDS);
        // 실패로 끝난 승인(Unanswered → 503)도 자리를 돌려준다 — 반납이 빠지면 상한 1 에서 다음 승인이 503 이 된다
        given(paymentClient.confirm(any(), any(), any()))
                .willThrow(new ResourceAccessException("refused", new ConnectException("refused")))
                .willReturn(ApiResponse.ok(new ConfirmReply(ConfirmReply.Result.PENDING, null)));
        confirm(rejected).andExpect(status().isServiceUnavailable());
        confirm(placedOrder(fixtures, product, 3)).andExpect(status().isOk());
    }

    /** 응모비 승인은 주문 승인과 같은 상한을 나눠 쓴다 — 주문 승인이 자리를 다 쓰고 있으면 응모비 승인도 응모를 바꾸기 전에 503 이고, 끝나면 자리를 돌려준다. */
    @Test
    @DisplayName("응모비 승인도 같은 상한 — 주문 승인이 차 있으면 응모를 바꾸기 전에 503, 실패로 끝나도 자리를 돌려준다")
    void drawEntryConfirmSharesTheLimit() throws Exception {
        OrderFixtures fixtures = new OrderFixtures(jdbcTemplate);
        Order waiting = placedOrder(fixtures, fixtures.preorderProduct(), 1);
        UUID member = fixtures.customer();
        UUID draw = openDraw(fixtures);
        UUID entry = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO draw_entries (id, campaign_id, customer_id, status, ship_to_name, ship_to_phone, ship_to_postal_code, ship_to_line1,
                                          created_at, updated_at)
                VALUES (?, ?, ?, 'AWAITING_PAYMENT', '홍길동', '010-0000-0000', '04524', '서울시', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""",
                bytes(entry), bytes(draw), bytes(member));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        given(paymentClient.confirm(any(), any(), any())).willAnswer(invocation -> {
            entered.countDown();
            release.await(10, TimeUnit.SECONDS);
            return ApiResponse.ok(new ConfirmReply(ConfirmReply.Result.PENDING, null));
        });
        CompletableFuture<Void> first = CompletableFuture.runAsync(() -> {
            try {
                confirm(waiting).andExpect(status().isOk());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();

        confirmEntry(member, draw).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error.code").value("DEPENDENCY_UNAVAILABLE"));
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM draw_entries WHERE id = ?", String.class, (Object) bytes(entry)))
                .isEqualTo("AWAITING_PAYMENT");

        release.countDown();
        first.get(10, TimeUnit.SECONDS);
        given(paymentClient.confirm(any(), any(), any()))
                .willThrow(new ResourceAccessException("refused", new ConnectException("refused")))
                .willReturn(ApiResponse.ok(new ConfirmReply(ConfirmReply.Result.PENDING, null)));
        confirmEntry(member, draw).andExpect(status().isServiceUnavailable());
        confirm(placedOrder(fixtures, fixtures.preorderProduct(), 1)).andExpect(status().isOk());
    }

    private UUID openDraw(OrderFixtures fixtures) {
        OrderFixtures.StockProduct product = fixtures.inStockProduct(1);
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO draw_campaigns (id, idempotency_key, product_id, option_id, title, product_title_snapshot, option_title_snapshot,
                                            entry_fee, winner_count, opens_at, closes_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, 't', 'p', 'o', 100, 1, UTC_TIMESTAMP(6) - INTERVAL 1 HOUR, UTC_TIMESTAMP(6) + INTERVAL 1 DAY,
                        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""",
                bytes(id), id.toString(), bytes(product.productId()), bytes(product.optionIds().getFirst()));
        return id;
    }

    private ResultActions confirmEntry(UUID member, UUID draw) throws Exception {
        return mockMvc.perform(post("/api/v1/draws/{drawId}/entries/me/payment-attempts/{tossOrderId}/confirm", draw, "draw_bulkhead_1")
                .with(TestAuth.customer(member))
                .header(BearerTokens.HEADER, BearerTokens.value(SESSION))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"paymentKey\":\"%s\",\"amount\":100}".formatted(PAYMENT)));
    }

    private Order placedOrder(OrderFixtures fixtures, PreorderProduct product, long position) {
        UUID customerId = fixtures.customer();
        UUID preorderId = fixtures.payablePreorder(customerId, product, position);
        Order order = new TransactionTemplate(transactionManager).execute(status -> ledger.place(
                OrderFixtures.preorderCommand(customerId, preorderId, product).toDraft(), EventCause.user()));
        PreorderStubs.stub(preorderClient, PreorderStubs.payable(preorderId, order.preorderToken(), customerId, product));
        return order;
    }

    private ResultActions confirm(Order order) throws Exception {
        return mockMvc.perform(post("/api/v1/orders/{orderToken}/payment-attempts/{tossOrderId}/confirm",
                order.orderToken().value(), UUID.randomUUID().toString())
                .with(TestAuth.customer(order.customerId()))
                .header(BearerTokens.HEADER, BearerTokens.value(SESSION))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"paymentKey\":\"%s\",\"amount\":%s}".formatted(PAYMENT, order.totalAmount().amount())));
    }

    private String statusOf(Order order) {
        return jdbcTemplate.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, bytes(order.id()));
    }
}
