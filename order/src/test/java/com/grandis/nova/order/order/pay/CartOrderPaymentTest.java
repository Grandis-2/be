package com.grandis.nova.order.order.pay;

import com.grandis.nova.common.security.BearerTokens;
import com.grandis.nova.common.web.ApiResponse;
import com.grandis.nova.order.client.payment.DeclineReason;
import com.grandis.nova.order.client.payment.PaymentAttempt;
import com.grandis.nova.order.client.payment.PaymentClient;
import com.grandis.nova.order.client.preorder.PreorderClient;
import com.grandis.nova.order.order.OrderLedger;
import com.grandis.nova.order.order.command.PlaceOrderCommand;
import com.grandis.nova.order.order.domain.enums.OrderSource;
import com.grandis.nova.order.order.domain.model.Order;
import com.grandis.nova.order.order.vo.EventCause;
import com.grandis.nova.order.stock.StockLedger;
import com.grandis.nova.order.support.OrderFixtures;
import com.grandis.nova.order.support.OrderIntegrationTest;
import com.grandis.nova.order.support.TestAuth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.grandis.nova.order.support.OrderFixtures.bytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 장바구니 주문의 결제 — 결제 준비가 장바구니 주문을 받고(기한은 주문의 10분), 승인 반영이 같은 트랜잭션에서 판매 확정 · 장바구니 차감을 한 번만 한다.
 * 대역 구성은 PaymentAttemptApiTest 와 같다 — Spring 컨텍스트(와 MySQL 컨테이너)를 함께 쓴다. 주문은 원장 · 재고 원장으로 직접 만든다.
 */
@OrderIntegrationTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class CartOrderPaymentTest {

    static final String SESSION = "cart-order-payment-test";
    static final String PROVIDER_ORDER_ID = "7a1c2d3e-4b5a-4c7d-8e9f-0a1b2c3d4e5f";
    static final BigDecimal PRICE = new BigDecimal("1250000");
    static final BigDecimal WARRANTY = new BigDecimal("199000");

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired OrderLedger ledger;
    @Autowired StockLedger stock;
    @Autowired PaymentResults results;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoBean PreorderClient preorderClient;
    @MockitoBean PaymentClient paymentClient;

    OrderFixtures fixtures;
    UUID customerId;
    UUID phone;
    UUID watch;
    Order order;

    /** 장바구니: 폰 보증 2 · 폰 3 · 시계 1. 주문: 폰 4(그중 보증 2) · 시계 1 — 폰 줄 하나는 1 이 남는다. */
    @BeforeEach
    void setUp() {
        fixtures = new OrderFixtures(jdbcTemplate);
        customerId = fixtures.customer();
        OrderFixtures.StockProduct product = fixtures.inStockProduct(2);
        List<UUID> options = product.optionIds();
        phone = options.get(0);
        watch = options.get(1);
        fixtures.stock(phone, 10, 0, 0);
        fixtures.stock(watch, 10, 0, 0);
        cartLine(phone, true, 2);
        cartLine(phone, false, 3);
        cartLine(watch, false, 1);
        order = place(List.of(
                new PlaceOrderCommand.Line(product.productId(), phone, 4, PRICE, 2, WARRANTY, "아이폰 17", "블랙"),
                new PlaceOrderCommand.Line(product.productId(), watch, 1, PRICE, 0, BigDecimal.ZERO, "워치", "실버")));
    }

    @Test
    @DisplayName("결제 준비는 장바구니 주문을 받는다 — 금액은 저장된 총액, preorder 에 묻지 않는다")
    void prepareAcceptsCartOrder() throws Exception {
        BigDecimal total = PRICE.multiply(BigDecimal.valueOf(5)).add(WARRANTY.multiply(BigDecimal.valueOf(2)));
        given(paymentClient.openCapture(any(), eq(BearerTokens.value(SESSION))))
                .willReturn(ApiResponse.ok(new PaymentAttempt(PROVIDER_ORDER_ID, total)));

        prepare().andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.amount").value(total.intValue()))
                .andExpect(jsonPath("$.data.orderName").value("아이폰 17 블랙 외 1건"));

        verifyNoInteractions(preorderClient);
    }

    @Test
    @DisplayName("기한(만든 때 + 10분)이 지난 장바구니 주문은 결제 준비 409 PAYMENT_WINDOW_EXPIRED — payment 를 부르지 않는다")
    void expiredCartOrderIsNotPayable() throws Exception {
        // 저장은 UTC 다 — JVM 시간대로 바인딩되는 Timestamp 대신 DB 의 UTC 로 기한을 지나게 한다
        jdbcTemplate.update("UPDATE orders SET payment_due_at = UTC_TIMESTAMP(6) - INTERVAL 1 SECOND WHERE id = ?", (Object) bytes(order.id()));

        prepare().andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("PAYMENT_WINDOW_EXPIRED"));

        verifyNoInteractions(paymentClient, preorderClient);
    }

    @Test
    @DisplayName("승인 반영은 판매 확정(확보 → 판매) · 장바구니 차감(0 이하면 삭제)을 같이 하고, 승인이 또 와도(응답 · 이벤트 겹침) 한 번뿐")
    void approvalSellsStockAndDeductsCartOnce(CapturedOutput output) {
        fixtures.forceAuthorizing(order.id(), PROVIDER_ORDER_ID);

        assertThat(results.settle(approved()).applied()).isTrue();
        assertThat(results.settle(approved()).applied()).as("중복 승인").isFalse();
        assertThat(output).as("정상 승인에는 재고 어긋남 경보가 없다").doesNotContain("재고 어긋남");

        assertThat(stockOf(phone)).containsExactly(10, 0, 4);
        assertThat(stockOf(watch)).containsExactly(10, 0, 1);
        assertThat(cart()).containsExactlyInAnyOrderEntriesOf(Map.of(phone + "/false", 1));
    }

    @Test
    @DisplayName("거절이면 재고 · 장바구니를 건드리지 않는다 — 주문은 결제 대기로 돌아가 확보를 쥔 채 다시 결제할 수 있다")
    void declineKeepsReservationAndCart() {
        fixtures.forceAuthorizing(order.id(), PROVIDER_ORDER_ID);

        results.settle(new PaymentSettlement(order.id(), PROVIDER_ORDER_ID, PaymentSettlement.Result.DECLINED, order.totalAmount().amount(),
                DeclineReason.CARD_REJECTED));

        assertThat(stockOf(phone)).containsExactly(10, 4, 0);
        assertThat(cart()).hasSize(3);
        assertThat(statusOf()).isEqualTo("AWAITING_PAYMENT");
    }

    /**
     * 재고 장부가 어긋나 판매 확정을 못 해도(확보가 모자람) 돈은 나갔으니 주문은 결제됨 · 장바구니도 뺀다 — 어긋난 옵션만 옮기지 않고 전용 ERROR.
     * 잠금 순서의 앞 옵션 · 뒤 옵션이 어긋난 경우를 모두 본다 — 앞에서 어긋나도 뒤 옵션은 판매로 옮겨야 한다(첫 어긋남에서 멈추지 않는다).
     */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void mismatchedStockStillSettlesAndAlerts(boolean frontMismatched, CapturedOutput output) {
        List<UUID> byLockOrder = new ArrayList<>(List.of(phone, watch));
        byLockOrder.sort((a, b) -> Arrays.compareUnsigned(bytes(a), bytes(b)));
        UUID broken = frontMismatched ? byLockOrder.get(0) : byLockOrder.get(1);
        UUID healthy = frontMismatched ? byLockOrder.get(1) : byLockOrder.get(0);
        int brokenQuantity = broken.equals(phone) ? 4 : 1;
        int healthyQuantity = healthy.equals(phone) ? 4 : 1;
        fixtures.forceAuthorizing(order.id(), PROVIDER_ORDER_ID);
        jdbcTemplate.update("UPDATE option_inventories SET stock_reserved = 0 WHERE option_id = ?", (Object) bytes(broken));

        assertThat(results.settle(approved()).applied()).isTrue();

        assertThat(statusOf()).isEqualTo("AWAITING_CONFIRMATION");
        assertThat(cart()).containsExactlyInAnyOrderEntriesOf(Map.of(phone + "/false", 1));
        assertThat(stockOf(healthy)).as("어긋나지 않은 옵션은 판매로").containsExactly(10, 0, healthyQuantity);
        assertThat(stockOf(broken)).as("어긋난 옵션은 그대로").containsExactly(10, 0, 0);
        List<String> alerts = output.getAll().lines().filter(line -> line.contains("재고 어긋남")).toList();
        assertThat(alerts).hasSize(1);
        assertThat(alerts.getFirst()).contains("ERROR").contains(order.id().toString()).contains(PROVIDER_ORDER_ID)
                .contains(broken + "=" + brokenQuantity).doesNotContain(healthy.toString());
    }

    @Test
    @DisplayName("주문 뒤 사용자가 지운 줄은 건너뛰고, 줄인 줄은 0 이하가 되어 지운다")
    void deductionToleratesLinesChangedAfterOrdering() {
        jdbcTemplate.update("DELETE FROM cart_items WHERE customer_id = ? AND option_id = ?", bytes(customerId), bytes(watch));
        jdbcTemplate.update("UPDATE cart_items SET quantity = 1 WHERE customer_id = ? AND option_id = ? AND warranty_selected = 0",
                bytes(customerId), bytes(phone));
        fixtures.forceAuthorizing(order.id(), PROVIDER_ORDER_ID);

        results.settle(approved());

        assertThat(cart()).isEmpty();
        assertThat(stockOf(watch)).containsExactly(10, 0, 1);
    }

    /**
     * 결제 반영(주문 행 → 장바구니 줄)과 같은 회원의 장바구니 주문 생성(장바구니 줄 → 앞 주문 행)이 엇갈리면 교착이다. 결제 반영이 희생자가
     * 되어도 새 트랜잭션에서 다시 해 한 번 반영된다 — 결정적으로 본다: 생성 쪽 트랜잭션이 장바구니 줄을 잠그고 재고 행 30개를 바꿔(InnoDB 는
     * 바꾼 행이 적은 쪽을 희생자로 고른다) 무겁게 둔 채, 결제 반영이 장바구니 줄에서 기다리는 것을 확인한 뒤 그 주문 행을 잠근다.
     */
    @Test
    void settlementDeadlockVictimIsRetriedInNewTransaction(CapturedOutput output) throws Exception {
        fixtures.forceAuthorizing(order.id(), PROVIDER_ORDER_ID);
        List<UUID> ballast = fixtures.inStockProduct(30).optionIds();
        ballast.forEach(option -> fixtures.stock(option, 1, 0, 0));
        CountDownLatch cartLocked = new CountDownLatch(1);
        CountDownLatch settlementWaiting = new CountDownLatch(1);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CompletableFuture<Void> placing = CompletableFuture.runAsync(() -> tx.executeWithoutResult(status -> {
            jdbcTemplate.queryForList("SELECT id FROM cart_items WHERE customer_id = ? FOR UPDATE", (Object) bytes(customerId));
            ballast.forEach(option -> jdbcTemplate.update("UPDATE option_inventories SET updated_at = UTC_TIMESTAMP(6) WHERE option_id = ?",
                    (Object) bytes(option)));
            cartLocked.countDown();
            await(settlementWaiting);
            jdbcTemplate.queryForList("SELECT status FROM orders WHERE id = ? FOR UPDATE", (Object) bytes(order.id()));
        }));
        await(cartLocked);
        CompletableFuture<Boolean> settling = CompletableFuture.supplyAsync(() -> results.settle(approved()).applied());
        waitForQuery("SELECT %FROM cart_items WHERE customer_id = %FOR UPDATE");
        settlementWaiting.countDown();

        assertThat(placing).as("무거운 쪽은 교착에서 살아남는다").succeedsWithin(Duration.ofSeconds(30));
        assertThat(settling.get(30, TimeUnit.SECONDS)).as("희생된 결제 반영은 다시 해 반영된다").isTrue();
        assertThat(output).contains("결제 결과 반영 교착, 다시 시도 1/");
        assertThat(statusOf()).isEqualTo("AWAITING_CONFIRMATION");
        assertThat(stockOf(phone)).containsExactly(10, 0, 4);
        assertThat(cart()).containsExactlyInAnyOrderEntriesOf(Map.of(phone + "/false", 1));
    }

    private void waitForQuery(String like) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            Long running = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE COMMAND = 'Query' AND INFO LIKE ?", Long.class, like);
            if (running != null && running > 0) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("그 문장이 기다리지 않았다: " + like);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("기다리다 시간 초과");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private Order place(List<PlaceOrderCommand.Line> lines) {
        PlaceOrderCommand command = new PlaceOrderCommand(customerId, OrderSource.CART, null, null, OrderFixtures.ADDRESS, lines);
        Map<UUID, Integer> quantities = new HashMap<>();
        lines.forEach(line -> quantities.put(line.optionId(), line.quantity()));
        return new TransactionTemplate(transactionManager).execute(status -> {
            stock.reserve(quantities);
            return ledger.place(command.toDraft(), EventCause.user());
        });
    }

    private PaymentSettlement approved() {
        return new PaymentSettlement(order.id(), PROVIDER_ORDER_ID, PaymentSettlement.Result.APPROVED, order.totalAmount().amount(), null);
    }

    private void cartLine(UUID option, boolean warranty, int quantity) {
        jdbcTemplate.update("""
                INSERT INTO cart_items (id, customer_id, option_id, warranty_selected, quantity, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, bytes(UUID.randomUUID()), bytes(customerId), bytes(option), warranty, quantity);
    }

    private ResultActions prepare() throws Exception {
        return mockMvc.perform(post("/api/v1/orders/{orderToken}/payment-attempts", order.orderToken().value())
                .with(TestAuth.customer(customerId)).header(BearerTokens.HEADER, BearerTokens.value(SESSION)));
    }

    /** [총량, 확보, 판매] */
    private List<Integer> stockOf(UUID option) {
        return jdbcTemplate.queryForObject("SELECT stock_total, stock_reserved, stock_sold FROM option_inventories WHERE option_id = ?",
                (rs, n) -> List.of(rs.getInt(1), rs.getInt(2), rs.getInt(3)), (Object) bytes(option));
    }

    /** "옵션/보증" → 수량 */
    private Map<String, Integer> cart() {
        Map<String, Integer> lines = new HashMap<>();
        jdbcTemplate.query("SELECT option_id, warranty_selected, quantity FROM cart_items WHERE customer_id = ?",
                rs -> {
                    lines.put(com.grandis.nova.common.UuidBinary.fromBytes(rs.getBytes(1)) + "/" + rs.getBoolean(2), rs.getInt(3));
                }, (Object) bytes(customerId));
        return lines;
    }

    private String statusOf() {
        return jdbcTemplate.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, (Object) bytes(order.id()));
    }
}
