package com.grandis.nova.order.order.cancel;

import com.grandis.nova.common.testing.Concurrently;
import com.grandis.nova.common.web.ApiResponse;
import com.grandis.nova.order.cart.domain.repository.CartStore;
import com.grandis.nova.order.client.catalog.CatalogClient;
import com.grandis.nova.order.client.catalog.CatalogOption;
import com.grandis.nova.order.client.catalog.CatalogOptions;
import com.grandis.nova.order.order.OrderLedger;
import com.grandis.nova.order.order.domain.model.Order;
import com.grandis.nova.order.order.domain.repository.OrderReader;
import com.grandis.nova.order.order.domain.repository.PaymentUnconfirmedCounts;
import com.grandis.nova.order.order.pay.PaymentResults;
import com.grandis.nova.order.order.pay.PaymentSettlement;
import com.grandis.nova.order.order.vo.EventCause;
import com.grandis.nova.order.support.OrderFixtures;
import com.grandis.nova.order.support.OrderIntegrationTest;
import com.grandis.nova.order.support.TestAuth;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.grandis.nova.order.support.OrderFixtures.bytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 결제 결과를 끝내 모른 승인 중 장바구니 · 바로 구매 주문의 취소(만든 뒤 20분)와, 그 뒤 늦게 온 승인의 자동 환불 · 환불 결과.
 * 대역 구성은 CartOrderCancelTest 와 같다 — Spring 컨텍스트(와 MySQL 컨테이너)를 함께 쓴다. 장바구니 주문은 API 로 만들고, 승인 중은 고정물로 만든다.
 * 공유 DB 에 다른 시험의 주문이 있을 수 있어 반환값(취소 수) 대신 이 주문들의 상태로 본다. 스위치는 꺼진 채(기본) cancelDue 를 직접 부른다 —
 * 늦은 승인의 자동 환불이 스위치와 상관없이 동작하는 것도 함께 본다.
 */
@OrderIntegrationTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class UnconfirmedCartOrderExpiryTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final BigDecimal PRICE = new BigDecimal("1250000");
    static final String SHIP_TO = """
            {"name":"홍길동","phone":"010-0000-0000","postalCode":"04524","line1":"서울시 중구 세종대로 110"}""";
    static final String UNRECEIVED_APPROVAL = "결제가 승인됐는데 주문이 받지 못했다";

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired UnconfirmedCartOrderExpiry expiry;
    @Autowired UnconfirmedCartOrderRelease release;
    @Autowired PaymentResults results;
    @Autowired RefundResults refundResults;
    @Autowired OrderReader orderReader;
    @Autowired OrderLedger ledger;
    @Autowired MeterRegistry registry;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoBean CatalogClient catalogClient;
    /** 쓰지 않지만 CartApiTest 와 같은 대역 구성으로 두어 Spring 컨텍스트를 함께 쓴다. */
    @MockitoSpyBean CartStore store;

    OrderFixtures fixtures;
    UUID customerId;
    Map<UUID, CatalogOption> catalog = new HashMap<>();

    @BeforeEach
    void setUp() {
        fixtures = new OrderFixtures(jdbcTemplate);
        customerId = fixtures.customer();
        given(catalogClient.getOptions(any(), any())).willAnswer(invocation -> {
            Collection<UUID> ids = invocation.getArgument(0);
            return ApiResponse.ok(new CatalogOptions(ids.stream().filter(catalog::containsKey).map(catalog::get).toList()));
        });
    }

    @Test
    @DisplayName("만든 뒤 20분이 지난 승인 중 주문을 취소 · 재고 반환 · 알림 한 건 — 결제창은 남기고, 두 번 돌아도 한 번")
    void cancelsStaleAuthorizingOrderOnce() {
        UUID phone = sellable(10);
        String order = authorizingCartOrder(phone, 3, "p-stale");
        Instant now = createdPlus20m(order);

        expiry.cancelDue(now);
        expiry.cancelDue(now);

        assertThat(column(order, "status", String.class)).isEqualTo("CANCELED");
        assertThat(column(order, "stock_released_at IS NOT NULL", Boolean.class)).isTrue();
        assertThat(column(order, "authorizing_provider_order_id", String.class)).isNull();
        assertThat(column(order, "unconfirmed_provider_order_id", String.class)).isEqualTo("p-stale");
        assertThat(lastEvent(order)).containsEntry("actor", "SYSTEM").containsEntry("reason", "PAYMENT_UNCONFIRMED")
                .containsEntry("from_status", "AUTHORIZING").containsEntry("to_status", "CANCELED");
        // 관리자 집계(countPaymentUnconfirmed)는 이 취소가 적은 시각으로 센다. 그 시각부터와 1µs 뒤부터를 한 문장으로 세어 차이를 본다 — 차이는
        // 정확히 그 마이크로초에 적힌 결제 확인 실패 행이다. 그 뒤의 다른 행(다른 시험의 행 포함)은 양쪽에 같이 들어 상쇄된다
        Instant canceledAt = orderReader.findById(UUID.fromString(order)).orElseThrow().stockReleasedAt();
        PaymentUnconfirmedCounts fromCanceledAt = orderReader.countPaymentUnconfirmed(canceledAt.plusNanos(1_000), canceledAt);
        assertThat(fromCanceledAt.last24h() - fromCanceledAt.last1h()).isEqualTo(1);
        assertThat(reserved(phone)).isZero();
        List<JsonNode> notices = outbox(order, "NOTIFICATION_REQUESTED");
        assertThat(notices).hasSize(1);
        assertThat(notices.getFirst().get("kind").asString()).isEqualTo("PAYMENT_UNCONFIRMED");
        assertThat(notices.getFirst().get("recipientIds").get(0).asString()).isEqualTo(customerId.toString());
        assertThat(notices.getFirst().get("target").get("type").asString()).isEqualTo("ORDER");
        assertThat(notices.getFirst().get("target").get("id").asString()).isEqualTo(order);
    }

    @Test
    @DisplayName("20분 전 · 결제 대기 · 바로 구매 아닌 사전예약은 건드리지 않는다 — 기한 == 기준도 지난 것으로 본다")
    void leavesOthersAlone() throws Exception {
        UUID phone = sellable(20);
        String due = authorizingCartOrder(phone, 1, "p-due");
        Instant now = createdPlus20m(due);
        UUID other = fixtures.customer();
        String notYet = authorizingCartOrderOf(other, phone, 2, "p-not-yet");
        setDue(notYet, dueOf(due).plusNanos(1_000));
        UUID third = fixtures.customer();
        String awaiting = placeCartOrderOf(third, phone, 3);
        setDue(awaiting, dueOf(due));
        OrderFixtures.PreorderProduct product = fixtures.preorderProduct();
        Order preorder = new TransactionTemplate(transactionManager).execute(status -> ledger.place(
                OrderFixtures.preorderCommand(customerId, OrderFixtures.preorderId(), product).toDraft(), EventCause.user()));
        fixtures.forceAuthorizing(preorder.id(), "p-preorder-stale");
        jdbcTemplate.update("UPDATE orders SET created_at = created_at - INTERVAL 1 DAY WHERE id = ?", (Object) bytes(preorder.id()));

        expiry.cancelDue(now);

        assertThat(column(due, "status", String.class)).as("기한 + 10분 == 기준").isEqualTo("CANCELED");
        assertThat(column(notYet, "status", String.class)).isEqualTo("AUTHORIZING");
        assertThat(column(awaiting, "status", String.class)).as("결제 대기는 결제 기한 만료가 맡는다").isEqualTo("AWAITING_PAYMENT");
        assertThat(column(preorder.id().toString(), "status", String.class)).as("사전예약은 고르지 않는다").isEqualTo("AUTHORIZING");
        assertThat(reserved(phone)).isEqualTo(2 + 3);
    }

    @Test
    @DisplayName("바로 구매 주문도 같다")
    void cancelsStaleBuyNowOrder() throws Exception {
        UUID phone = sellable(10);
        String order = placeBuyNowOrder(phone, 2);
        fixtures.forceAuthorizing(UUID.fromString(order), "p-buy-now");

        expiry.cancelDue(createdPlus20m(order));

        assertThat(column(order, "status", String.class)).isEqualTo("CANCELED");
        assertThat(reserved(phone)).isZero();
    }

    @Test
    @DisplayName("사전예약 주문은 원장이 받지 않는다 — 결과를 끝까지 기다린다")
    void preorderOrderIsRefused() {
        OrderFixtures.PreorderProduct product = fixtures.preorderProduct();
        Order preorder = new TransactionTemplate(transactionManager).execute(status -> ledger.place(
                OrderFixtures.preorderCommand(customerId, OrderFixtures.preorderId(), product).toDraft(), EventCause.user()));
        fixtures.forceAuthorizing(preorder.id(), "p-preorder");

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).execute(status ->
                release.cancel(preorder.id(), EventCause.system("PAYMENT_UNCONFIRMED"))))
                .isInstanceOf(IllegalStateException.class);
        assertThat(column(preorder.id().toString(), "status", String.class)).isEqualTo("AUTHORIZING");
    }

    @Test
    @DisplayName("취소한 뒤 그 결제창의 승인이 늦게 오면 자동 환불을 한 번 요청한다 — 결과 이벤트가 겹쳐 와도 한 번, 경보 없음")
    void lateApprovalRequestsRefundOnce(CapturedOutput output) throws Exception {
        UUID phone = sellable(10);
        String order = authorizingCartOrder(phone, 1, "p-late");
        expiry.cancelDue(createdPlus20m(order));
        double unmatched = attention("UNMATCHED_APPROVAL");

        List<Concurrently.Outcome<Object>> outcomes =
                Concurrently.run(4, i -> () -> (Object) results.settle(approval(order, "p-late", PRICE)));
        results.settle(approval(order, "p-late", PRICE));

        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome.error()).isNull());
        assertThat(column(order, "status", String.class)).isEqualTo("CANCELED");
        assertThat(column(order, "auto_refund_requested_at IS NOT NULL", Boolean.class)).isTrue();
        List<JsonNode> refunds = outbox(order, "ORDER_REFUND_REQUESTED");
        assertThat(refunds).hasSize(1);
        assertThat(refunds.getFirst().get("amount").decimalValue()).isEqualByComparingTo(PRICE);
        assertThat(lastEvent(order)).containsEntry("reason", "PAYMENT_LATE_REFUND")
                .containsEntry("from_status", "CANCELED").containsEntry("to_status", "CANCELED");
        assertThat(attention("UNMATCHED_APPROVAL")).isEqualTo(unmatched);
        assertThat(output.getAll()).doesNotContain(UNRECEIVED_APPROVAL);
    }

    @Test
    @DisplayName("결제창 · 금액이 다르거나 결과를 모른 채 취소한 주문이 아니면 자동 환불하지 않고 경보한다 — 금액 불일치는 그 결제창의 늦은 승인일 때만")
    void ineligibleLateApprovalIsAlerted(CapturedOutput output) throws Exception {
        UUID phone = sellable(10);
        String otherAttempt = authorizingCartOrder(phone, 1, "p-own");
        expiry.cancelDue(createdPlus20m(otherAttempt));
        String otherAmount = authorizingCartOrderOf(fixtures.customer(), phone, 1, "p-amount");
        expiry.cancelDue(createdPlus20m(otherAmount));
        String userCanceled = placeCartOrderOf(fixtures.customer(), phone, 1);
        mockMvc.perform(post("/api/v1/orders/{id}/cancel", userCanceled).with(TestAuth.customer(customerOf(userCanceled))))
                .andExpect(status().isOk());
        double unmatched = attention("UNMATCHED_APPROVAL");
        double amountMismatch = attention("AMOUNT_MISMATCH");

        results.settle(approval(otherAttempt, "p-someone-else", PRICE));
        results.settle(approval(otherAmount, "p-amount", PRICE.add(BigDecimal.ONE)));
        results.settle(approval(userCanceled, "p-user", PRICE));
        results.settle(approval(userCanceled, "p-user", PRICE.add(BigDecimal.ONE)));

        for (String order : List.of(otherAttempt, otherAmount, userCanceled)) {
            assertThat(outbox(order, "ORDER_REFUND_REQUESTED")).as(order).isEmpty();
            assertThat(column(order, "auto_refund_requested_at IS NULL", Boolean.class)).isTrue();
        }
        assertThat(attention("UNMATCHED_APPROVAL")).as("결제창 · 상태 불일치(금액도 다른 사용자 취소 주문 포함)").isEqualTo(unmatched + 3);
        assertThat(attention("AMOUNT_MISMATCH")).as("금액 불일치").isEqualTo(amountMismatch + 1);
        assertThat(output.getAll()).contains(UNRECEIVED_APPROVAL);
    }

    @Test
    @DisplayName("승인과 20분 취소가 겹치면 한쪽만 — 승인이 이기면 결제됨 · 환불 없음, 취소가 이기면 취소 · 환불 요청 한 번. 어느 쪽이든 확보는 0")
    void approvalAndCancelRacingLeaveOne() throws Exception {
        for (int round = 0; round < 5; round++) {
            UUID phone = sellable(10);
            String attempt = "p-race-" + UUID.randomUUID();
            String order = authorizingCartOrderOf(fixtures.customer(), phone, 2, attempt);
            Instant now = createdPlus20m(order);

            List<Concurrently.Outcome<Object>> outcomes = Concurrently.run(2, i -> () -> i == 0
                    ? (Object) results.settle(approval(order, attempt, PRICE.multiply(BigDecimal.TWO)))
                    : (Object) expiry.cancelDue(now));

            assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome.error()).isNull());
            String status = column(order, "status", String.class);
            assertThat(status).isIn("AWAITING_CONFIRMATION", "CANCELED");
            assertThat(reserved(phone)).isZero();
            assertThat(outbox(order, "ORDER_REFUND_REQUESTED")).hasSize(status.equals("CANCELED") ? 1 : 0);
        }
    }

    @Test
    @DisplayName("승인이 먼저 반영되면 취소는 지나간다 — 결제됨 · 판매 확정, 환불 요청 없음")
    void approvalFirstThenCancelLeavesPaidOrder() {
        UUID phone = sellable(10);
        String order = authorizingCartOrder(phone, 2, "p-approve-first");

        results.settle(approval(order, "p-approve-first", PRICE.multiply(BigDecimal.TWO)));
        expiry.cancelDue(createdPlus20m(order));

        assertThat(column(order, "status", String.class)).isEqualTo("AWAITING_CONFIRMATION");
        assertThat(column(order, "stock_released_at IS NULL", Boolean.class)).isTrue();
        assertThat(sold(phone)).isEqualTo(2);
        assertThat(outbox(order, "ORDER_REFUND_REQUESTED")).isEmpty();
        assertThat(outbox(order, "NOTIFICATION_REQUESTED")).isEmpty();
    }

    @Test
    @DisplayName("취소가 먼저 반영되면 승인은 자동 환불로 간다 — 확보 반환 · 판매 없음, 환불 요청 한 번")
    void cancelFirstThenApprovalRequestsRefund() {
        UUID phone = sellable(10);
        String order = authorizingCartOrder(phone, 2, "p-cancel-first");

        expiry.cancelDue(createdPlus20m(order));
        results.settle(approval(order, "p-cancel-first", PRICE.multiply(BigDecimal.TWO)));

        assertThat(column(order, "status", String.class)).isEqualTo("CANCELED");
        assertThat(reserved(phone)).isZero();
        assertThat(sold(phone)).isZero();
        assertThat(outbox(order, "ORDER_REFUND_REQUESTED")).hasSize(1);
    }

    @Test
    @DisplayName("반환할 확보가 모자라면 취소도 되돌리고(승인 중 그대로 · 알림 없음) 재고 어긋남 지표를 센다")
    void stockShortfallRollsBackCancel(CapturedOutput output) {
        UUID phone = sellable(10);
        String order = authorizingCartOrder(phone, 3, "p-short");
        jdbcTemplate.update("UPDATE option_inventories SET stock_reserved = 0 WHERE option_id = ?", (Object) bytes(phone));
        double mismatches = registry.get("order.stock.mismatch").tag("site", "unconfirmed-cancel").counter().count();

        expiry.cancelDue(createdPlus20m(order));

        assertThat(column(order, "status", String.class)).isEqualTo("AUTHORIZING");
        assertThat(column(order, "unconfirmed_provider_order_id", String.class)).isNull();
        assertThat(outbox(order, "NOTIFICATION_REQUESTED")).isEmpty();
        assertThat(registry.get("order.stock.mismatch").tag("site", "unconfirmed-cancel").counter().count())
                .isEqualTo(mismatches + 1);
        assertThat(output.getAll()).contains("재고 어긋남");
    }

    @Test
    @DisplayName("자동 환불을 요청한 주문에 다른 결제창의 승인이 오면 중복이 아니다 — 환불 요청을 더 적지 않고 경보한다")
    void otherAttemptAfterAutoRefundIsAlerted(CapturedOutput output) {
        UUID phone = sellable(10);
        String order = autoRefunded(phone, "p-first");
        double unmatched = attention("UNMATCHED_APPROVAL");

        results.settle(approval(order, "p-second", PRICE));

        assertThat(outbox(order, "ORDER_REFUND_REQUESTED")).hasSize(1);
        assertThat(attention("UNMATCHED_APPROVAL")).isEqualTo(unmatched + 1);
        assertThat(output.getAll()).contains(UNRECEIVED_APPROVAL);
    }

    @Test
    @DisplayName("자동 환불이 끝나면 취소 그대로, 확정 실패하면 환불 실패 시각 · 이력을 남기고 경보 — 같은 실패를 다시 받아도 한 번")
    void autoRefundResults() {
        UUID phone = sellable(10);
        String refunded = autoRefunded(phone, "p-refunded");
        String failed = autoRefunded(phone, "p-failed");
        double autoRefundFailed = attention("AUTO_REFUND_FAILED");

        refundResults.settle(new RefundSettlement(UUID.fromString(refunded), RefundSettlement.Result.REFUNDED, PRICE));
        refundResults.settle(new RefundSettlement(UUID.fromString(failed), RefundSettlement.Result.FAILED, PRICE));
        refundResults.settle(new RefundSettlement(UUID.fromString(failed), RefundSettlement.Result.FAILED, PRICE));

        assertThat(column(refunded, "status", String.class)).isEqualTo("CANCELED");
        assertThat(column(refunded, "refund_failed_at IS NULL", Boolean.class)).isTrue();
        assertThat(column(failed, "status", String.class)).isEqualTo("CANCELED");
        assertThat(column(failed, "refund_failed_at IS NOT NULL", Boolean.class)).isTrue();
        assertThat(lastEvent(failed)).containsEntry("reason", "REFUND_FAILED");
        assertThat(attention("AUTO_REFUND_FAILED")).isEqualTo(autoRefundFailed + 1);
    }

    @Test
    @DisplayName("자동 환불한 주문에 승인이 또 와도 중복이다 — 환불 요청을 다시 적지 않고 경보도 없다")
    void approvalAfterAutoRefundIsDuplicate(CapturedOutput output) {
        UUID phone = sellable(10);
        String order = autoRefunded(phone, "p-again");
        refundResults.settle(new RefundSettlement(UUID.fromString(order), RefundSettlement.Result.REFUNDED, PRICE));
        double unmatched = attention("UNMATCHED_APPROVAL");

        results.settle(approval(order, "p-again", PRICE));

        assertThat(outbox(order, "ORDER_REFUND_REQUESTED")).hasSize(1);
        assertThat(attention("UNMATCHED_APPROVAL")).isEqualTo(unmatched);
    }

    /** 결과를 모른 채 취소한 뒤 늦은 승인으로 자동 환불을 요청한 주문. */
    private String autoRefunded(UUID phone, String attempt) {
        String order = authorizingCartOrderOf(fixtures.customer(), phone, 1, attempt);
        expiry.cancelDue(createdPlus20m(order));
        results.settle(approval(order, attempt, PRICE));
        assertThat(outbox(order, "ORDER_REFUND_REQUESTED")).hasSize(1);
        return order;
    }

    private PaymentSettlement approval(String orderId, String providerOrderId, BigDecimal amount) {
        return new PaymentSettlement(UUID.fromString(orderId), providerOrderId, PaymentSettlement.Result.APPROVED, amount,
                Instant.parse("2026-10-24T03:00:00Z"), null);
    }

    private double attention(String reason) {
        return registry.get("order.payment.attention").tag("reason", reason).counter().count();
    }

    private UUID sellable(int stock) {
        OrderFixtures.StockProduct product = fixtures.inStockProduct(1);
        UUID option = product.optionIds().getFirst();
        fixtures.stock(option, stock, 0, 0);
        catalog.put(option, new CatalogOption(option, product.productId(), "아이폰 17", "A3104", "블랙 / 256GB", "SKU-" + option, PRICE,
                "ACTIVE", "IN_STOCK", "ACTIVE", true, true, new CatalogOption.Warranty(false, BigDecimal.ZERO), null, null));
        return option;
    }

    private String authorizingCartOrder(UUID option, int quantity, String attempt) {
        return authorizingCartOrderOf(customerId, option, quantity, attempt);
    }

    private String authorizingCartOrderOf(UUID customer, UUID option, int quantity, String attempt) {
        String order = placeCartOrderOf(customer, option, quantity);
        fixtures.forceAuthorizing(UUID.fromString(order), attempt);
        return order;
    }

    private String placeCartOrderOf(UUID customer, UUID option, int quantity) {
        jdbcTemplate.update("""
                INSERT INTO cart_items (id, customer_id, option_id, warranty_selected, quantity, created_at, updated_at)
                VALUES (?, ?, ?, 0, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, bytes(UUID.randomUUID()), bytes(customer), bytes(option), quantity);
        try {
            String body = mockMvc.perform(post("/api/v1/orders").with(TestAuth.customer(customer)).contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"source":"CART","items":[%s],"shipTo":%s}""".formatted(item(option, quantity), SHIP_TO)))
                    .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
            return JSON.readTree(body).get("data").get("orderId").asString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String placeBuyNowOrder(UUID option, int quantity) throws Exception {
        String body = mockMvc.perform(post("/api/v1/orders").with(TestAuth.customer(customerId)).contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .content("""
                                {"source":"BUY_NOW","items":[%s],"shipTo":%s}""".formatted(item(option, quantity), SHIP_TO)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JSON.readTree(body).get("data").get("orderId").asString();
    }

    private static String item(UUID option, int quantity) {
        return """
                {"variantId":"%s","quantity":%d,"expectedUnitPrice":1250000}""".formatted(option, quantity);
    }

    private UUID customerOf(String orderId) {
        return orderReader.findById(UUID.fromString(orderId)).orElseThrow().customerId();
    }

    private Instant dueOf(String orderId) {
        return orderReader.findById(UUID.fromString(orderId)).orElseThrow().paymentDueAt();
    }

    /** 만든 때 + 20분 = 기한(만든 때 + 10분) + 10분. */
    private Instant createdPlus20m(String orderId) {
        return dueOf(orderId).plus(Duration.ofMinutes(10));
    }

    /** JVM 시간대로 바인딩되지 않게 UTC 문자열로 넣는다(저장은 UTC). */
    private void setDue(String orderId, Instant due) {
        jdbcTemplate.update("UPDATE orders SET payment_due_at = ? WHERE id = UUID_TO_BIN(?)",
                due.toString().replace("T", " ").replace("Z", ""), orderId);
    }

    private <T> T column(String orderId, String expression, Class<T> type) {
        return jdbcTemplate.queryForObject("SELECT " + expression + " FROM orders WHERE id = UUID_TO_BIN(?)", type, orderId);
    }

    private int reserved(UUID option) {
        return jdbcTemplate.queryForObject("SELECT stock_reserved FROM option_inventories WHERE option_id = ?", Integer.class,
                (Object) bytes(option));
    }

    private int sold(UUID option) {
        return jdbcTemplate.queryForObject("SELECT stock_sold FROM option_inventories WHERE option_id = ?", Integer.class,
                (Object) bytes(option));
    }

    private Map<String, Object> lastEvent(String orderId) {
        return jdbcTemplate.queryForMap("""
                SELECT e.actor, e.reason, e.from_status, e.to_status FROM order_events e JOIN orders o ON o.id = e.order_id
                 WHERE o.id = UUID_TO_BIN(?) ORDER BY e.event_sequence DESC LIMIT 1""", orderId);
    }

    private List<JsonNode> outbox(String orderId, String eventType) {
        return jdbcTemplate.queryForList("""
                SELECT payload FROM order_outbox_events
                 WHERE aggregate_type = 'ORDER' AND aggregate_id = UUID_TO_BIN(?) AND event_type = ? ORDER BY id
                """, String.class, orderId, eventType).stream().map(JSON::readTree).toList();
    }
}
