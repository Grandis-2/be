package com.grandis.nova.order.order.api;

import com.grandis.nova.common.testing.Concurrently;
import com.grandis.nova.common.testing.Concurrently.Outcome;
import com.grandis.nova.common.web.ApiResponse;
import com.grandis.nova.order.cart.domain.repository.CartStore;
import com.grandis.nova.order.client.catalog.CatalogClient;
import com.grandis.nova.order.client.catalog.CatalogOption;
import com.grandis.nova.order.client.catalog.CatalogOptions;
import com.grandis.nova.order.client.payment.DeclineReason;
import com.grandis.nova.order.order.domain.repository.OrderReader;
import com.grandis.nova.order.order.pay.PaymentResults;
import com.grandis.nova.order.order.pay.PaymentSettlement;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 바로 구매 주문(POST /api/v1/orders, source=BUY_NOW) — 생성 · 결제 결과 반영 · 미결제 취소, 장바구니 주문과의 독립.
 * 기한 만료 · 만료와 사용자 취소의 겹침은 CartOrderCancelTest 가 본다(만료 처리의 진입점이 패키지 안이다).
 * 대역 구성은 CartOrderApiTest · CartOrderCancelTest 와 같다 — Spring 컨텍스트(와 MySQL 컨테이너)를 함께 쓴다.
 */
@OrderIntegrationTest
@AutoConfigureMockMvc
class BuyNowOrderApiTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final BigDecimal PRICE = new BigDecimal("1250000");
    static final BigDecimal WARRANTY = new BigDecimal("199000");
    static final String SHIP_TO = """
            {"name":"홍길동","phone":"010-0000-0000","postalCode":"04524","line1":"서울시 중구 세종대로 110"}""";

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired PaymentResults results;
    @Autowired OrderReader orderReader;
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
    @DisplayName("옵션 하나의 보증 포함 · 미포함 줄로 주문 — 주문상품 한 줄로 합치고, 재고를 확보하고, 기한은 10분(201). 장바구니는 읽지도 바꾸지도 않는다")
    void placesBuyNowOrderReservingStock() throws Exception {
        UUID phone = sellable(10);
        cartLine(customerId, phone, false, 1);
        Instant before = Instant.now();

        JsonNode order = data(place(item(phone, 2, false), item(phone, 1, true)).andExpect(status().isCreated()));

        assertThat(order.get("source").asString()).isEqualTo("BUY_NOW");
        assertThat(order.get("status").asString()).isEqualTo("AWAITING_PAYMENT");
        assertThat(order.get("reused").asBoolean()).isFalse();
        assertThat(order.get("totalAmount").decimalValue()).isEqualByComparingTo(PRICE.multiply(BigDecimal.valueOf(3)).add(WARRANTY));
        Instant due = Instant.parse(order.get("paymentDueAt").asString());
        assertThat(due).isBetween(before.plus(Duration.ofMinutes(10)).minusSeconds(1), Instant.now().plus(Duration.ofMinutes(10)));
        JsonNode items = order.get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("quantity").asInt()).isEqualTo(3);
        assertThat(items.get(0).get("warrantyQuantity").asInt()).isEqualTo(1);
        assertThat(reserved(phone)).isEqualTo(3);
        assertThat(cart(customerId)).as("장바구니는 그대로").containsExactlyEntriesOf(Map.of(phone + "/false", 1));
    }

    @Test
    @DisplayName("누를 때마다 새 주문이다 — 같은 요청이어도 기존 주문을 돌려주지 않고 앞 주문도 취소하지 않는다")
    void everyRequestCreatesNewOrder() throws Exception {
        UUID phone = sellable(10);

        String first = data(place(item(phone, 1, false)).andExpect(status().isCreated())).get("orderId").asString();
        String second = data(place(item(phone, 1, false)).andExpect(status().isCreated())).get("orderId").asString();

        assertThat(second).isNotEqualTo(first);
        assertThat(orderStatus(first)).isEqualTo("AWAITING_PAYMENT");
        assertThat(reserved(phone)).isEqualTo(2);
    }

    @Test
    @DisplayName("마지막 재고를 동시에 두 번 누르면 하나만 201, 나머지는 409 INSUFFICIENT_STOCK — 확보는 총량을 넘지 않는다")
    void concurrentRequestsForLastUnitCreateOne() throws Exception {
        UUID phone = sellable(1);

        List<Outcome<Integer>> outcomes = Concurrently.run(2, i -> () -> place(item(phone, 1, false)).andReturn().getResponse().getStatus());

        assertThat(outcomes).extracting(Outcome::value).containsExactlyInAnyOrder(201, 409);
        assertThat(reserved(phone)).isEqualTo(1);
        assertThat(orderCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("화면에서 본 단가 · 보증가가 지금 값과 다르면 409 PRICE_CHANGED 와 지금 값 — 주문 · 확보 없음")
    void changedPriceIsConflict() throws Exception {
        UUID phone = sellable(10);

        expectError(place("""
                {"variantId":"%s","quantity":1,"warranty":true,"expectedUnitPrice":1200000,"expectedWarrantyPrice":199000}""".formatted(phone)),
                409, "PRICE_CHANGED")
                .andExpect(jsonPath("$.error.details.items[0].variantId").value(phone.toString()))
                .andExpect(jsonPath("$.error.details.items[0].unitPrice").value(1250000));

        assertThat(orderCount()).isZero();
        assertThat(reserved(phone)).isZero();
    }

    @Test
    @DisplayName("재고가 모자라면 409 INSUFFICIENT_STOCK 과 그 옵션 — 보증 포함 · 미포함 합계로 본다, 주문 · 확보 없음")
    void shortageIsConflict() throws Exception {
        UUID phone = sellable(2);

        expectError(place(item(phone, 2, false), item(phone, 1, true)), 409, "INSUFFICIENT_STOCK")
                .andExpect(jsonPath("$.error.message").value("주문 전체 수량을 확보할 수 없습니다."))
                .andExpect(jsonPath("$.error.details.variantIds[0]").value(phone.toString()));

        assertThat(orderCount()).isZero();
        assertThat(reserved(phone)).isZero();
    }

    @Test
    @DisplayName("살 수 없는 옵션이면 409 STATE_CONFLICT 와 이유 — 판매 중지 · 판매 종료 · 보증 중단")
    void unavailableOptionIsConflict() throws Exception {
        UUID paused = sellable(10);
        catalog.put(paused, copy(catalog.get(paused), "PAUSED", true));
        UUID gone = sellable(10);
        catalog.remove(gone);
        UUID warrantyStopped = sellable(10);
        catalog.put(warrantyStopped, copy(catalog.get(warrantyStopped), "ACTIVE", false));

        expectError(place(item(paused, 1, false)), 409, "STATE_CONFLICT")
                .andExpect(jsonPath("$.error.details.items[0].reason").value("PAUSED"));
        expectError(place(item(gone, 1, false)), 409, "STATE_CONFLICT")
                .andExpect(jsonPath("$.error.details.items[0].reason").value("DISCONTINUED"));
        expectError(place(item(warrantyStopped, 1, true)), 409, "STATE_CONFLICT")
                .andExpect(jsonPath("$.error.details.items[0].reason").value("WARRANTY_UNAVAILABLE"));
        assertThat(orderCount()).isZero();
    }

    /** 바로 구매는 장바구니 담기를 거치지 않는다 — 사전예약 상품을 막는 것은 주문 때의 판정(일반 판매가 아니면 HIDDEN) 하나다. */
    @Test
    void preorderModeOptionIsConflict() throws Exception {
        UUID option = sellable(10);
        CatalogOption o = catalog.get(option);
        catalog.put(option, new CatalogOption(o.optionId(), o.productId(), o.productTitle(), o.optionTitle(), o.sku(), o.price(), o.optionStatus(),
                "PREORDER", o.productStatus(), o.visible(), o.registrationCompleted(), o.warranty(), o.imageUrl()));

        expectError(place(item(option, 1, false)), 409, "STATE_CONFLICT")
                .andExpect(jsonPath("$.error.details.items[0].reason").value("HIDDEN"));

        assertThat(orderCount()).isZero();
        assertThat(reserved(option)).isZero();
    }

    @Test
    @DisplayName("요청 검증 — 옵션 둘 · 예약 토큰 동시 · 줄 없음 · 같은 줄 두 번 · 수량 범위(1~99)는 400 — 주문 · 확보 없음")
    void invalidRequestsAreRejected() throws Exception {
        UUID phone = sellable(10);
        UUID watch = sellable(10);

        expectViolation(place(item(phone, 1, false), item(watch, 1, false)), "items");
        expectViolation(perform("""
                {"source":"BUY_NOW","preorderId":"%s","items":[%s],"shipTo":%s}""".formatted(UUID.randomUUID(), item(phone, 1, false), SHIP_TO)),
                "preorderId");
        expectViolation(perform("""
                {"source":"BUY_NOW","shipTo":%s}""".formatted(SHIP_TO)), "items");
        expectViolation(place(item(phone, 1, false), item(phone, 2, false)), "items");
        expectViolation(place(item(phone, 0, false)), "items[0].quantity");
        expectViolation(place(item(phone, 100, false)), "items[0].quantity");
        assertThat(orderCount()).isZero();
        assertThat(reserved(phone)).isZero();
    }

    @Test
    @DisplayName("결제 승인 반영은 판매 확정(확보 → 판매)만 한다 — 장바구니는 같은 옵션 줄이 있어도 그대로")
    void approvalSellsStockAndKeepsCart() throws Exception {
        UUID phone = sellable(10);
        cartLine(customerId, phone, false, 3);
        cartLine(customerId, phone, true, 1);
        String order = data(place(item(phone, 2, false), item(phone, 1, true)).andExpect(status().isCreated())).get("orderId").asString();
        fixtures.forceAuthorizing(UUID.fromString(order), "p-buy-now");

        assertThat(results.settle(settlement(order, "p-buy-now", PaymentSettlement.Result.APPROVED, null)).applied()).isTrue();

        assertThat(orderStatus(order)).isEqualTo("AWAITING_CONFIRMATION");
        assertThat(stockOf(phone)).containsExactly(10, 0, 3);
        assertThat(cart(customerId)).containsExactlyInAnyOrderEntriesOf(Map.of(phone + "/false", 3, phone + "/true", 1));
    }

    @Test
    @DisplayName("결제 거절이면 결제 대기로 돌아가 확보를 쥔 채 다시 결제할 수 있다")
    void declineReturnsToAwaitingPayment() throws Exception {
        UUID phone = sellable(10);
        String order = data(place(item(phone, 2, false)).andExpect(status().isCreated())).get("orderId").asString();
        fixtures.forceAuthorizing(UUID.fromString(order), "p-buy-now-decline");

        results.settle(settlement(order, "p-buy-now-decline", PaymentSettlement.Result.DECLINED, DeclineReason.CARD_REJECTED));

        assertThat(orderStatus(order)).isEqualTo("AWAITING_PAYMENT");
        assertThat(stockOf(phone)).containsExactly(10, 2, 0);
    }

    @Test
    @DisplayName("미결제 바로 구매 주문은 사용자가 취소한다 — 200 CANCELED · 재고 반환, 다시 불러도 반환은 한 번")
    void userCancelReleasesStockOnce() throws Exception {
        UUID phone = sellable(10);
        String order = data(place(item(phone, 3, false)).andExpect(status().isCreated())).get("orderId").asString();

        cancel(order).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CANCELED"));
        cancel(order).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CANCELED"));

        assertThat(reserved(phone)).isZero();
        assertThat(stockReleased(order)).isTrue();
    }

    @Test
    @DisplayName("남의 바로 구매 주문은 404, 승인 중이면 409 PAYMENT_IN_PROGRESS — 어느 쪽도 바꾸지 않는다")
    void othersOrAuthorizingOrderIsNotCanceled() throws Exception {
        UUID phone = sellable(10);
        String order = data(place(item(phone, 1, false)).andExpect(status().isCreated())).get("orderId").asString();

        mockMvc.perform(post("/api/v1/orders/{id}/cancel", order).with(TestAuth.customer(fixtures.customer())))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
        fixtures.forceAuthorizing(UUID.fromString(order), "p-buy-now-cancel");
        cancel(order).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("PAYMENT_IN_PROGRESS"));

        assertThat(orderStatus(order)).isEqualTo("AUTHORIZING");
        assertThat(reserved(phone)).isEqualTo(1);
    }

    @Test
    @DisplayName("장바구니 주문과 독립 — 바로 구매가 결제 대기 장바구니 주문을 취소하지 않고, 새 장바구니 주문도 바로 구매 주문을 취소하지 않는다")
    void independentOfCartOrders() throws Exception {
        UUID phone = sellable(10);
        UUID watch = sellable(10);
        cartLine(customerId, phone, false, 1);
        String cartOrder = data(perform("""
                {"source":"CART","items":[%s],"shipTo":%s}""".formatted(item(phone, 1, false), SHIP_TO))
                .andExpect(status().isCreated())).get("orderId").asString();

        String buyNow = data(place(item(phone, 2, false)).andExpect(status().isCreated())).get("orderId").asString();
        assertThat(orderStatus(cartOrder)).as("바로 구매는 장바구니 주문을 건드리지 않는다").isEqualTo("AWAITING_PAYMENT");

        cartLine(customerId, watch, false, 1);
        String nextCartOrder = data(perform("""
                {"source":"CART","items":[%s],"shipTo":%s}""".formatted(item(watch, 1, false), SHIP_TO))
                .andExpect(status().isCreated())).get("orderId").asString();

        assertThat(orderStatus(cartOrder)).as("장바구니 주문끼리는 앞 주문을 바꾼다").isEqualTo("CANCELED");
        assertThat(orderStatus(buyNow)).as("바로 구매 주문은 그 규칙 밖").isEqualTo("AWAITING_PAYMENT");
        assertThat(orderStatus(nextCartOrder)).isEqualTo("AWAITING_PAYMENT");
        assertThat(reserved(phone)).isEqualTo(2);
        assertThat(reserved(watch)).isEqualTo(1);
    }

    /** 일반 판매 · 공개 · 판매 중 · 보증 제공 옵션. */
    private UUID sellable(int stock) {
        OrderFixtures.StockProduct product = fixtures.inStockProduct(1);
        UUID option = product.optionIds().getFirst();
        fixtures.stock(option, stock, 0, 0);
        catalog.put(option, new CatalogOption(option, product.productId(), "아이폰 17", "블랙 / 256GB", "SKU-" + option, PRICE,
                "ACTIVE", "IN_STOCK", "ACTIVE", true, true, new CatalogOption.Warranty(true, WARRANTY), null));
        return option;
    }

    private static CatalogOption copy(CatalogOption o, String optionStatus, boolean warrantyOffered) {
        return new CatalogOption(o.optionId(), o.productId(), o.productTitle(), o.optionTitle(), o.sku(), o.price(), optionStatus,
                o.saleMode(), o.productStatus(), o.visible(), o.registrationCompleted(),
                new CatalogOption.Warranty(warrantyOffered, warrantyOffered ? WARRANTY : BigDecimal.ZERO), o.imageUrl());
    }

    private PaymentSettlement settlement(String orderId, String providerOrderId, PaymentSettlement.Result result, DeclineReason reason) {
        BigDecimal amount = orderReader.findById(UUID.fromString(orderId)).orElseThrow().totalAmount().amount();
        return new PaymentSettlement(UUID.fromString(orderId), providerOrderId, result, amount, reason);
    }

    private static String item(UUID option, int quantity, boolean warranty) {
        return warranty
                ? """
                  {"variantId":"%s","quantity":%d,"warranty":true,"expectedUnitPrice":1250000,"expectedWarrantyPrice":199000}"""
                .formatted(option, quantity)
                : """
                  {"variantId":"%s","quantity":%d,"expectedUnitPrice":1250000}""".formatted(option, quantity);
    }

    private ResultActions place(String... items) throws Exception {
        return perform("""
                {"source":"BUY_NOW","items":[%s],"shipTo":%s}""".formatted(String.join(",", items), SHIP_TO));
    }

    private ResultActions perform(String body) throws Exception {
        return mockMvc.perform(post("/api/v1/orders").with(TestAuth.customer(customerId))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions cancel(String orderId) throws Exception {
        return mockMvc.perform(post("/api/v1/orders/{id}/cancel", orderId).with(TestAuth.customer(customerId)));
    }

    private void cartLine(UUID customer, UUID option, boolean warranty, int quantity) {
        jdbcTemplate.update("""
                INSERT INTO cart_items (id, customer_id, option_id, warranty_selected, quantity, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, bytes(UUID.randomUUID()), bytes(customer), bytes(option), warranty, quantity);
    }

    /** "옵션/보증" → 수량 */
    private Map<String, Integer> cart(UUID customer) {
        Map<String, Integer> lines = new HashMap<>();
        jdbcTemplate.query("SELECT option_id, warranty_selected, quantity FROM cart_items WHERE customer_id = ?",
                rs -> {
                    lines.put(com.grandis.nova.common.UuidBinary.fromBytes(rs.getBytes(1)) + "/" + rs.getBoolean(2), rs.getInt(3));
                }, (Object) bytes(customer));
        return lines;
    }

    private int reserved(UUID option) {
        return jdbcTemplate.queryForObject("SELECT stock_reserved FROM option_inventories WHERE option_id = ?", Integer.class,
                (Object) bytes(option));
    }

    /** [총량, 확보, 판매] */
    private List<Integer> stockOf(UUID option) {
        return jdbcTemplate.queryForObject("SELECT stock_total, stock_reserved, stock_sold FROM option_inventories WHERE option_id = ?",
                (rs, n) -> List.of(rs.getInt(1), rs.getInt(2), rs.getInt(3)), (Object) bytes(option));
    }

    private String orderStatus(String orderId) {
        return jdbcTemplate.queryForObject("SELECT status FROM orders WHERE id = UUID_TO_BIN(?)", String.class, orderId);
    }

    private boolean stockReleased(String orderId) {
        return jdbcTemplate.queryForObject("SELECT stock_released_at IS NOT NULL FROM orders WHERE id = UUID_TO_BIN(?)", Boolean.class, orderId);
    }

    private long orderCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM orders WHERE customer_id = ?", Long.class, (Object) bytes(customerId));
    }

    private static JsonNode data(ResultActions actions) throws Exception {
        return JSON.readTree(actions.andReturn().getResponse().getContentAsString()).get("data");
    }

    private static ResultActions expectError(ResultActions actions, int status, String code) throws Exception {
        return actions.andExpect(status().is(status)).andExpect(jsonPath("$.error.code").value(code));
    }

    private static void expectViolation(ResultActions actions, String field) throws Exception {
        actions.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details.violations[0].field").value(field));
    }
}
