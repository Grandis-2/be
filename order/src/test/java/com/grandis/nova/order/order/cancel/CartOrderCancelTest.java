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
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
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
@ExtendWith(OutputCaptureExtension.class)
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

        expiry.expireDue(now);

        // 공유 DB 에 다른 시험이 남긴 기한 지난 주문이 있을 수 있어 반환값(취소 수) 대신 이 주문들의 상태로 본다
        assertThat(column(due, "status", String.class)).as("기한 == now 는 지났다").isEqualTo("CANCELED");
        assertThat(lastEvent(due)).containsEntry("actor", "SYSTEM").containsEntry("reason", "PAYMENT_EXPIRED");
        assertThat(column(notYet, "status", String.class)).isEqualTo("AWAITING_PAYMENT");
        assertThat(column(authorizing, "status", String.class)).as("승인 중은 결과를 기다린다").isEqualTo("AUTHORIZING");
        assertThat(reserved(phone)).isEqualTo(3 + 4);
        expiry.expireDue(now);
        assertThat(reserved(phone)).as("두 번 돌아도 다시 반환하지 않는다").isEqualTo(7);
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
        assertThat(List.of(outcomes.get(0).value(), outcomes.get(2).value())).as("사용자 취소는 모두 200").containsOnly(200);
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

    /** 반환할 확보가 모자라면(재고 장부 어긋남) 취소하지 않고 "재고 어긋남" 경보 한 줄 — 주문 · 반환하려던 수량 · 그때의 확보를 싣는다. */
    @Test
    void releaseMismatchAlertsAndLeavesOrderUnpaid(CapturedOutput output) throws Exception {
        UUID phone = sellable(10);
        String order = placeCartOrder(phone, 3);
        jdbcTemplate.update("UPDATE option_inventories SET stock_reserved = 1 WHERE option_id = ?", (Object) bytes(phone));

        cancel(customerId, order).andExpect(status().isInternalServerError());

        assertThat(column(order, "status", String.class)).isEqualTo("AWAITING_PAYMENT");
        List<String> alerts = output.getAll().lines().filter(line -> line.contains("재고 어긋남")).toList();
        assertThat(alerts).hasSize(1);
        assertThat(alerts.getFirst()).contains("ERROR").contains(idOf(order).toString()).contains("optionId=" + phone).contains("quantity=3")
                .contains("reservedNow=1");
    }

    /**
     * 옵션 둘인 주문에서 잠금 순서 뒤 옵션의 반환이 모자라면, 경보는 그 옵션만 싣는다 — 앞 옵션은 이미 뺐다가 롤백되므로 그 숫자는 복구에 쓸 수 없다.
     * 롤백 뒤 앞 옵션의 확보도 그대로다.
     */
    @Test
    void releaseMismatchOnLaterOptionAlertsOnlyThatOption(CapturedOutput output) throws Exception {
        List<UUID> options = new ArrayList<>(List.of(sellable(10), sellable(10)));
        options.sort((a, b) -> Arrays.compareUnsigned(bytes(a), bytes(b)));
        UUID front = options.get(0);
        UUID back = options.get(1);
        cartLine(customerId, front, 2);
        cartLine(customerId, back, 3);
        String body = placeRequest(customerId, item(front, 2) + "," + item(back, 3)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String order = JSON.readTree(body).get("data").get("orderId").asString();
        jdbcTemplate.update("UPDATE option_inventories SET stock_reserved = 1 WHERE option_id = ?", (Object) bytes(back));

        cancel(customerId, order).andExpect(status().isInternalServerError());

        assertThat(reserved(front)).as("롤백 — 앞 옵션도 그대로").isEqualTo(2);
        List<String> alerts = output.getAll().lines().filter(line -> line.contains("재고 어긋남")).toList();
        assertThat(alerts).hasSize(1);
        assertThat(alerts.getFirst()).contains("optionId=" + back).contains("quantity=3").contains("reservedNow=1").doesNotContain(front.toString());
    }

    /** 만료는 (기한, id) 로 이어 읽는다 — 앞에서 늘 실패하는 주문(데이터 어긋남)이 있어도 뒤 주문들을 처리한다. 쪽 크기 1 로 쪽 넘김까지 태운다. */
    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)   // 이어 읽기가 고장 나면 같은 쪽을 끝없이 돈다 — 그래도 끝나게
    void expirySkipsFailingOrderAndContinues() {
        UUID phone = sellable(10);
        UUID other = fixtures.customer();
        String broken = placeCartOrder(phone, 3);
        String healthy = placeCartOrderOf(other, phone, 2);
        Instant now = dueAt(healthy);
        setDue(broken, now.minusSeconds(1));
        jdbcTemplate.update("UPDATE option_inventories SET stock_reserved = 2 WHERE option_id = ?", (Object) bytes(phone));

        expiry.expireDue(now, 1);

        assertThat(column(broken, "status", String.class)).as("어긋난 주문은 남는다").isEqualTo("AWAITING_PAYMENT");
        assertThat(column(healthy, "status", String.class)).as("뒤 주문은 처리된다").isEqualTo("CANCELED");
        assertThat(reserved(phone)).isZero();
    }

    /** 같은 기한(µs 까지 같음)의 주문은 id 로 가른다 — 쪽 경계에 걸려도 빠지지 않는다. 앞(id 가 작은) 주문이 늘 실패해도 뒤 주문을 처리한다. */
    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void expiryOrdersSameDueById(CapturedOutput output) {
        UUID phone = sellable(10);
        String broken = placeCartOrder(phone, 3);
        String healthy = placeCartOrderOf(fixtures.customer(), phone, 2);
        assertThat(Arrays.compareUnsigned(bytes(idOf(broken)), bytes(idOf(healthy)))).as("broken 이 id 순으로 앞").isNegative();
        Instant due = dueAt(healthy);
        setDue(broken, due);
        jdbcTemplate.update("UPDATE option_inventories SET stock_reserved = 2 WHERE option_id = ?", (Object) bytes(phone));

        expiry.expireDue(due, 1);

        assertThat(column(broken, "status", String.class)).isEqualTo("AWAITING_PAYMENT");
        assertThat(column(healthy, "status", String.class)).isEqualTo("CANCELED");
        assertThat(output).as("만료 쪽은 경보를 겹쳐 남기지 않는다 — 스택 없는 WARN 한 줄")
                .contains("결제 기한 만료 처리 건너뜀 — 재고 어긋남").doesNotContain("결제 기한 만료 처리 실패");
    }

    /**
     * 같은 구성 · 값으로 다시 주문하는데, 재사용 후보를 읽은 뒤 잠그기 전에 결제 시작이 그 주문을 승인 중으로 바꾸면 옛 상태로 돌려주지 않고
     * 409 PAYMENT_IN_PROGRESS 다. 결정적으로 본다: 결제 쪽 트랜잭션이 승인 중으로 바꾼 채 미커밋, 재주문이 그 행 잠금에서 기다린 뒤 커밋.
     */
    @Test
    void reuseCandidateTurningAuthorizingIsPaymentInProgress() throws Exception {
        UUID phone = sellable(10);
        String first = placeCartOrder(phone, 2);
        CountDownLatch authorized = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CompletableFuture<Void> paying = CompletableFuture.runAsync(() -> tx.executeWithoutResult(status -> {
            jdbcTemplate.update("""
                    UPDATE orders SET status = 'AUTHORIZING', authorizing_provider_order_id = 'p-reuse'
                     WHERE order_token = ? AND status = 'AWAITING_PAYMENT'""", first);
            authorized.countDown();
            await(commit);
        }));
        await(authorized);
        CompletableFuture<String> again = CompletableFuture.supplyAsync(() -> {
            try {
                return placeRequest(customerId, item(phone, 2)).andReturn().getResponse().getContentAsString();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        waitForQuery("SELECT status FROM orders WHERE id = %FOR UPDATE");
        commit.countDown();
        paying.get(20, TimeUnit.SECONDS);

        String body = again.get(20, TimeUnit.SECONDS);
        assertThat(JSON.readTree(body).get("error").get("code").asString()).isEqualTo("PAYMENT_IN_PROGRESS");
        assertThat(reserved(phone)).isEqualTo(2);
    }

    /**
     * 같은 구성 · 값으로 다시 주문하는데 그 주문을 다른 취소(사용자 취소 · 만료)가 먼저 취소하면 돌려주지 않는다 — 잠가 다시 본다. 결정적으로 본다:
     * 취소 쪽 트랜잭션이 취소 · 반환한 채 커밋하지 않고, 재주문이 그 주문 행 잠금에서 기다리는 것을 확인한 뒤 커밋한다 → 새 주문(201).
     */
    @Test
    void reuseCandidateCanceledMeanwhileIsNotReturned() throws Exception {
        UUID phone = sellable(10);
        String first = placeCartOrder(phone, 2);
        CountDownLatch canceled = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CompletableFuture<Void> canceling = CompletableFuture.runAsync(() -> tx.executeWithoutResult(status -> {
            assertThat(release.cancel(idOf(first), EventCause.user()).applied()).isTrue();
            canceled.countDown();
            await(commit);
        }));
        await(canceled);
        CompletableFuture<String> again = CompletableFuture.supplyAsync(() -> {
            try {
                return placeRequest(customerId, item(phone, 2)).andReturn().getResponse().getContentAsString();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        waitForQuery("SELECT status FROM orders WHERE id = %FOR UPDATE");
        commit.countDown();
        canceling.get(20, TimeUnit.SECONDS);

        String body = again.get(20, TimeUnit.SECONDS);
        assertThat(JSON.readTree(body).get("data").get("reused").asBoolean()).isFalse();
        assertThat(JSON.readTree(body).get("data").get("orderId").asString()).isNotEqualTo(first);
        assertThat(reserved(phone)).as("앞 주문 반환 한 번 + 새 확보").isEqualTo(2);
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

    private void waitForQuery(String like) {
        Awaitility.await("그 문장이 기다리지 않았다: " + like).atMost(Duration.ofSeconds(10)).until(() -> {
            Long running = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE COMMAND = 'Query' AND INFO LIKE ?", Long.class, like);
            return running != null && running > 0;
        });
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
