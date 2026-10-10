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
import com.grandis.nova.order.order.vo.EventCause;
import com.grandis.nova.order.order.vo.OrderToken;
import com.grandis.nova.order.support.OrderFixtures;
import com.grandis.nova.order.support.OrderIntegrationTest;
import com.grandis.nova.order.support.TestAuth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.grandis.nova.order.support.OrderFixtures.bytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 미결제 장바구니 주문 취소 — 사용자 취소(POST /api/v1/orders/{id}/cancel)와 결제 기한 만료. 반환은 한 번이다(취소와 같은 UPDATE 의 반환 표식).
 * 대역 구성은 CartApiTest · CartOrderApiTest 와 같다 — Spring 컨텍스트(와 MySQL 컨테이너)를 함께 쓴다. 장바구니 주문은 API 로 만든다.
 */
@OrderIntegrationTest
@AutoConfigureMockMvc
class CartOrderCancelTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final BigDecimal PRICE = new BigDecimal("1250000");
    static final String SHIP_TO = """
            {"name":"홍길동","phone":"010-0000-0000","postalCode":"04524","line1":"서울시 중구 세종대로 110"}""";

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired CartOrderExpiry expiry;
    @Autowired UnpaidCartOrderRelease release;
    @Autowired OrderReader orderReader;
    @Autowired OrderLedger ledger;
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
    @DisplayName("결제 대기면 그 자리에서 취소 · 재고 반환(200 CANCELED), 장바구니는 그대로. 다시 불러도 200 이고 두 번 반환하지 않는다")
    void userCancelReleasesStockOnce() throws Exception {
        UUID phone = sellable(10);
        String order = placeCartOrder(phone, 3);
        assertThat(reserved(phone)).isEqualTo(3);

        cancel(customerId, order).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderId").value(order))
                .andExpect(jsonPath("$.data.status").value("CANCELED"));
        cancel(customerId, order).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CANCELED"));

        assertThat(reserved(phone)).isZero();
        assertThat(column(order, "stock_released_at IS NOT NULL", Boolean.class)).isTrue();
        assertThat(lastEvent(order)).containsEntry("actor", "USER").containsEntry("to_status", "CANCELED");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cart_items WHERE customer_id = ?", Long.class,
                (Object) bytes(customerId))).as("미결제 취소는 장바구니를 그대로 둔다").isEqualTo(1);
    }

    @Test
    @DisplayName("승인 중이면 409 PAYMENT_IN_PROGRESS, 결제됨이면 409 STATE_CONFLICT — 어느 쪽도 바꾸지 않는다")
    void authorizingOrPaidOrderIsNotCanceled() throws Exception {
        UUID phone = sellable(10);
        String authorizing = placeCartOrder(phone, 1);
        fixtures.forceAuthorizing(idOf(authorizing), "p-cancel");

        cancel(customerId, authorizing).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("PAYMENT_IN_PROGRESS"));
        assertThat(column(authorizing, "status", String.class)).isEqualTo("AUTHORIZING");

        fixtures.forceStatus(idOf(authorizing), "AWAITING_CONFIRMATION");
        cancel(customerId, authorizing).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("STATE_CONFLICT"));
        assertThat(reserved(phone)).isEqualTo(1);
    }

    @Test
    @DisplayName("남의 주문 · 없는 주문은 404, 사전예약 주문은 예약 취소로 — 409 STATE_CONFLICT")
    void othersMissingAndPreorderOrders() throws Exception {
        UUID phone = sellable(10);
        String order = placeCartOrder(phone, 1);

        cancel(fixtures.customer(), order).andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
        cancel(customerId, "not-a-token").andExpect(status().isNotFound());

        OrderFixtures.PreorderProduct product = fixtures.preorderProduct();
        UUID preorderId = fixtures.payablePreorder(customerId, product, 1);
        Order preorder = new TransactionTemplate(transactionManager).execute(status -> ledger.place(
                OrderFixtures.preorderCommand(customerId, preorderId, product).toDraft(), EventCause.user()));
        cancel(customerId, preorder.orderToken().value()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("STATE_CONFLICT"));
        assertThat(reserved(phone)).isEqualTo(1);
    }

    @Test
    @DisplayName("만료 처리는 기한 <= now 인 결제 대기만 취소 · 반환 — 기한 == now 포함, 기한 전 · 승인 중은 그대로")
    void expiryCancelsDueAwaitingOrdersOnly() {
        UUID phone = sellable(20);
        String due = placeCartOrder(phone, 2);
        UUID other = fixtures.customer();
        String notYet = placeCartOrderOf(other, phone, 3);
        UUID third = fixtures.customer();
        String authorizing = placeCartOrderOf(third, phone, 4);
        fixtures.forceAuthorizing(idOf(authorizing), "p-expiry");
        Instant now = dueAt(due);
        setDue(notYet, now.plusNanos(1_000));
        setDue(authorizing, now.minusSeconds(60));

        assertThat(expiry.expireDue(now)).isEqualTo(1);

        assertThat(column(due, "status", String.class)).as("기한 == now 는 지났다").isEqualTo("CANCELED");
        assertThat(lastEvent(due)).containsEntry("actor", "SYSTEM").containsEntry("reason", "PAYMENT_EXPIRED");
        assertThat(column(notYet, "status", String.class)).isEqualTo("AWAITING_PAYMENT");
        assertThat(column(authorizing, "status", String.class)).as("승인 중은 결과를 기다린다").isEqualTo("AUTHORIZING");
        assertThat(reserved(phone)).isEqualTo(3 + 4);
        assertThat(expiry.expireDue(now)).as("두 번 돌아도 다시 반환하지 않는다").isZero();
        assertThat(reserved(phone)).isEqualTo(7);
    }

    @Test
    @DisplayName("사용자 취소와 만료가 겹쳐도 반환은 한 번 — 확보가 음수가 되거나 남지 않는다")
    void concurrentUserCancelAndExpiryReleaseOnce() throws Exception {
        UUID phone = sellable(10);
        String order = placeCartOrder(phone, 2);
        Instant now = dueAt(order);

        List<Concurrently.Outcome<Integer>> outcomes = Concurrently.run(4, i -> () -> i % 2 == 0
                ? cancel(customerId, order).andReturn().getResponse().getStatus()
                : expiry.expireDue(now));

        assertThat(outcomes).allSatisfy(o -> assertThat(o.error()).isNull());
        assertThat(reserved(phone)).isZero();
        assertThat(column(order, "status", String.class)).isEqualTo("CANCELED");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM order_events e JOIN orders o ON o.id = e.order_id WHERE o.order_token = ? AND e.to_status = 'CANCELED'""",
                Long.class, order)).as("취소 이력도 하나").isEqualTo(1);
    }

    /**
     * 새 장바구니 주문이 앞 주문을 바꾸려는데, 읽은 뒤 잠그기 전에 다른 취소(만료 · 사용자 취소)가 먼저 취소하면 그 주문은 지나간다 —
     * 반환은 그쪽이 했으므로 새 주문의 확보만 남는다. 결정적으로 본다: 만료 쪽 트랜잭션이 앞 주문을 취소 · 반환한 채 커밋하지 않고, 새 주문이
     * 그 주문 행 잠금에서 기다리는 것을 확인한 뒤 커밋한다.
     */
    @Test
    void previousOrderCanceledByAnotherWhileReplacingIsSkipped() throws Exception {
        UUID phone = sellable(10);
        UUID watch = sellable(10);
        String first = placeCartOrder(phone, 2);
        cartLine(customerId, watch, 1);
        CountDownLatch canceled = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CompletableFuture<Void> expiring = CompletableFuture.runAsync(() -> tx.executeWithoutResult(status -> {
            assertThat(release.cancel(idOf(first), EventCause.system(CartOrderExpiry.EXPIRED_REASON)).applied()).isTrue();
            canceled.countDown();
            await(commit);
        }));
        await(canceled);
        CompletableFuture<Integer> replacing = CompletableFuture.supplyAsync(() -> {
            try {
                return placeRequest(customerId, item(watch, 1)).andReturn().getResponse().getStatus();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        waitForQuery("SELECT status FROM orders WHERE id = %FOR UPDATE");
        commit.countDown();
        expiring.get(20, TimeUnit.SECONDS);

        assertThat(replacing.get(20, TimeUnit.SECONDS)).isEqualTo(201);
        assertThat(reserved(phone)).as("앞 주문의 반환은 만료 쪽이 한 번").isZero();
        assertThat(reserved(watch)).isEqualTo(1);
    }

    private UUID sellable(int stock) {
        OrderFixtures.StockProduct product = fixtures.inStockProduct(1);
        UUID option = product.optionIds().getFirst();
        fixtures.stock(option, stock, 0, 0);
        catalog.put(option, new CatalogOption(option, product.productId(), "아이폰 17", "블랙 / 256GB", "SKU-" + option, PRICE,
                "ACTIVE", "IN_STOCK", "ACTIVE", true, true, new CatalogOption.Warranty(false, BigDecimal.ZERO), null));
        return option;
    }

    private String placeCartOrder(UUID option, int quantity) {
        return placeCartOrderOf(customerId, option, quantity);
    }

    private String placeCartOrderOf(UUID customer, UUID option, int quantity) {
        cartLine(customer, option, quantity);
        try {
            String body = placeRequest(customer, item(option, quantity)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
            return JSON.readTree(body).get("data").get("orderId").asString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void cartLine(UUID customer, UUID option, int quantity) {
        jdbcTemplate.update("""
                INSERT INTO cart_items (id, customer_id, option_id, warranty_selected, quantity, created_at, updated_at)
                VALUES (?, ?, ?, 0, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, bytes(UUID.randomUUID()), bytes(customer), bytes(option), quantity);
    }

    private static String item(UUID option, int quantity) {
        return """
                {"variantId":"%s","quantity":%d,"expectedUnitPrice":1250000}""".formatted(option, quantity);
    }

    private ResultActions placeRequest(UUID customer, String item) throws Exception {
        return mockMvc.perform(post("/api/v1/orders").with(TestAuth.customer(customer)).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"source":"CART","items":[%s],"shipTo":%s}""".formatted(item, SHIP_TO)));
    }

    private ResultActions cancel(UUID customer, String orderToken) throws Exception {
        return mockMvc.perform(post("/api/v1/orders/{id}/cancel", orderToken).with(TestAuth.customer(customer)));
    }

    private UUID idOf(String orderToken) {
        return orderReader.findByOrderToken(OrderToken.parse(orderToken).orElseThrow()).orElseThrow().id();
    }

    private Instant dueAt(String orderToken) {
        return orderReader.findByOrderToken(OrderToken.parse(orderToken).orElseThrow()).orElseThrow().paymentDueAt();
    }

    /** JVM 시간대로 바인딩되지 않게 UTC 문자열로 넣는다(저장은 UTC). */
    private void setDue(String orderToken, Instant due) {
        jdbcTemplate.update("UPDATE orders SET payment_due_at = ? WHERE order_token = ?",
                due.toString().replace("T", " ").replace("Z", ""), orderToken);
    }

    private <T> T column(String orderToken, String expression, Class<T> type) {
        return jdbcTemplate.queryForObject("SELECT " + expression + " FROM orders WHERE order_token = ?", type, orderToken);
    }

    private int reserved(UUID option) {
        return jdbcTemplate.queryForObject("SELECT stock_reserved FROM option_inventories WHERE option_id = ?", Integer.class,
                (Object) bytes(option));
    }

    private Map<String, Object> lastEvent(String orderToken) {
        return jdbcTemplate.queryForMap("""
                SELECT e.actor, e.reason, e.to_status FROM order_events e JOIN orders o ON o.id = e.order_id
                 WHERE o.order_token = ? ORDER BY e.event_sequence DESC LIMIT 1""", orderToken);
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
}
