package com.grandis.nova.order.order.api;

import com.grandis.nova.common.testing.Concurrently;
import com.grandis.nova.common.testing.Concurrently.Outcome;
import com.grandis.nova.common.web.ApiResponse;
import com.grandis.nova.order.cart.domain.repository.CartStore;
import com.grandis.nova.order.client.catalog.CatalogClient;
import com.grandis.nova.order.client.catalog.CatalogOption;
import com.grandis.nova.order.client.catalog.CatalogOptions;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 장바구니 주문 생성(POST /api/v1/orders, source=CART). catalog 는 대역, 장바구니 줄 · 재고는 표에 직접 심는다.
 * 대역 구성은 CartApiTest 와 같다 — Spring 컨텍스트(와 MySQL 컨테이너)를 함께 쓴다.
 */
@OrderIntegrationTest
@AutoConfigureMockMvc
class CartOrderApiTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final BigDecimal PRICE = new BigDecimal("1250000");
    static final BigDecimal WARRANTY = new BigDecimal("199000");
    static final String SHIP_TO = """
            {"name":"홍길동","phone":"010-0000-0000","postalCode":"04524","line1":"서울시 중구 세종대로 110"}""";

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
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
    @DisplayName("고른 장바구니 줄로 주문 — 같은 옵션의 보증 포함 · 미포함은 한 줄로 합치고, 재고를 전량 확보하고, 기한은 10분, 장바구니는 그대로")
    void placesCartOrderMergingWarrantyLinesAndReservingStock() throws Exception {
        UUID phone = sellable(10);
        UUID watch = sellable(5);
        cartLine(phone, false, 2);
        cartLine(phone, true, 1);
        cartLine(watch, false, 4);
        UUID notChosen = sellable(5);
        cartLine(notChosen, false, 1);
        Instant before = Instant.now();

        JsonNode order = data(place(item(phone, 2, false), item(phone, 1, true), item(watch, 4, false))
                .andExpect(status().isCreated()));

        assertThat(order.get("source").asString()).isEqualTo("CART");
        assertThat(order.get("status").asString()).isEqualTo("AWAITING_PAYMENT");
        assertThat(order.get("totalAmount").decimalValue())
                .isEqualByComparingTo(PRICE.multiply(BigDecimal.valueOf(3)).add(WARRANTY).add(PRICE.multiply(BigDecimal.valueOf(4))));
        Instant due = Instant.parse(order.get("paymentDueAt").asString());
        assertThat(due).isBetween(before.plus(Duration.ofMinutes(10)).minusSeconds(1), Instant.now().plus(Duration.ofMinutes(10)));
        Map<String, JsonNode> items = byVariant(order.get("items"));
        assertThat(items).hasSize(2);
        JsonNode merged = items.get(phone.toString());
        assertThat(merged.get("quantity").asInt()).isEqualTo(3);
        assertThat(merged.get("warrantyQuantity").asInt()).isEqualTo(1);
        assertThat(merged.get("warrantyUnitPrice").decimalValue()).isEqualByComparingTo(WARRANTY);
        assertThat(merged.get("lineAmount").decimalValue()).isEqualByComparingTo(PRICE.multiply(BigDecimal.valueOf(3)).add(WARRANTY));
        assertThat(items.get(watch.toString()).get("warrantyQuantity").asInt()).isZero();
        assertThat(items.get(watch.toString()).get("warrantyUnitPrice").decimalValue()).isEqualByComparingTo("0");

        assertThat(reserved(phone)).isEqualTo(3);
        assertThat(reserved(watch)).isEqualTo(4);
        assertThat(reserved(notChosen)).as("고르지 않은 줄은 확보하지 않는다").isZero();
        assertThat(cartQuantities()).as("장바구니는 결제 성공 때 뺀다").hasSize(4);

        JsonNode detail = data(mockMvc.perform(get("/api/v1/orders/{id}", order.get("orderId").asString())
                .with(TestAuth.customer(customerId))).andExpect(status().isOk()));
        assertThat(byVariant(detail.get("items")).get(phone.toString()).get("warrantyQuantity").asInt()).isEqualTo(1);
        assertThat(detail.get("paymentDueAt").asString()).isEqualTo(order.get("paymentDueAt").asString());
    }

    @Test
    @DisplayName("같은 구성으로 다시 보내면 새 주문 · 새 확보 없이 기존 주문(200) — 구성이 다르면 새 주문")
    void sameCompositionReusesOpenOrder() throws Exception {
        UUID phone = sellable(10);
        cartLine(phone, false, 2);

        String first = data(place(item(phone, 2, false)).andExpect(status().isCreated())).get("orderId").asString();
        JsonNode again = data(place(item(phone, 2, false)).andExpect(status().isOk()));

        assertThat(again.get("orderId").asString()).isEqualTo(first);
        assertThat(reserved(phone)).isEqualTo(2);

        jdbcTemplate.update("UPDATE cart_items SET quantity = 3 WHERE customer_id = ?", (Object) bytes(customerId));
        String other = data(place(item(phone, 3, false)).andExpect(status().isCreated())).get("orderId").asString();
        assertThat(other).isNotEqualTo(first);
        assertThat(reserved(phone)).isEqualTo(5);
    }

    @Test
    @DisplayName("기한이 지난 주문은 다시 쓰지 않는다 — 같은 구성이어도 새 주문")
    void expiredOrderIsNotReused() throws Exception {
        UUID phone = sellable(10);
        cartLine(phone, false, 1);
        String first = data(place(item(phone, 1, false)).andExpect(status().isCreated())).get("orderId").asString();
        // 저장은 UTC 다 — JVM 시간대(KST)로 바인딩되는 Timestamp 를 쓰지 않고 DB 의 UTC 로 기한을 지나게 한다
        jdbcTemplate.update("UPDATE orders SET payment_due_at = UTC_TIMESTAMP(6) - INTERVAL 1 SECOND WHERE order_token = ?", first);

        String second = data(place(item(phone, 1, false)).andExpect(status().isCreated())).get("orderId").asString();

        assertThat(second).isNotEqualTo(first);
    }

    @Test
    @DisplayName("고른 줄이 지금 장바구니와 다르면 409 CART_CHANGED — 수량이 다름 · 없는 줄 · 보증 여부가 다름")
    void selectionMustMatchCurrentCart() throws Exception {
        UUID phone = sellable(10);
        cartLine(phone, false, 2);

        expectError(place(item(phone, 3, false)), 409, "CART_CHANGED");
        expectError(place(item(phone, 2, true)), 409, "CART_CHANGED");
        expectError(place(item(sellable(10), 1, false)), 409, "CART_CHANGED");
        assertThat(reserved(phone)).isZero();
        assertThat(orderCount()).isZero();
    }

    @Test
    @DisplayName("화면에서 본 단가 · 보증가가 지금 값과 다르면 409 PRICE_CHANGED 와 지금 값")
    void changedPriceIsReportedWithCurrentPrice() throws Exception {
        UUID phone = sellable(10);
        cartLine(phone, true, 1);

        expectError(place(String.format("""
                {"variantId":"%s","quantity":1,"warranty":true,"expectedUnitPrice":1200000,"expectedWarrantyPrice":199000}""", phone)),
                409, "PRICE_CHANGED")
                .andExpect(jsonPath("$.error.details.items[0].variantId").value(phone.toString()))
                .andExpect(jsonPath("$.error.details.items[0].unitPrice").value(1250000))
                .andExpect(jsonPath("$.error.details.items[0].warrantyPrice").value(199000));
        expectError(place(String.format("""
                {"variantId":"%s","quantity":1,"warranty":true,"expectedUnitPrice":1250000,"expectedWarrantyPrice":99000}""", phone)),
                409, "PRICE_CHANGED");
        assertThat(orderCount()).isZero();
    }

    @Test
    @DisplayName("살 수 없는 줄이 있으면 409 STATE_CONFLICT 와 줄마다 이유 — 판매 중지 · 숨김 · 판매 종료 · 보증 중단")
    void unavailableLinesAreRejectedWithReasons() throws Exception {
        UUID paused = sellable(10);
        UUID hidden = sellable(10);
        UUID gone = sellable(10);
        UUID warrantyStopped = sellable(10);
        for (UUID option : List.of(paused, hidden, gone)) {
            cartLine(option, false, 1);
        }
        cartLine(warrantyStopped, true, 1);
        catalog.put(paused, copy(catalog.get(paused), "ACTIVE", "PAUSED", true, true));
        catalog.put(hidden, copy(catalog.get(hidden), "ACTIVE", "ACTIVE", false, true));
        catalog.remove(gone);
        catalog.put(warrantyStopped, copy(catalog.get(warrantyStopped), "ACTIVE", "ACTIVE", true, false));

        JsonNode error = JSON.readTree(expectError(place(item(paused, 1, false), item(hidden, 1, false), item(gone, 1, false),
                item(warrantyStopped, 1, true)), 409, "STATE_CONFLICT").andReturn().getResponse().getContentAsString());

        Map<String, String> reasons = new HashMap<>();
        error.get("error").get("details").get("items").forEach(i -> reasons.put(i.get("variantId").asString(), i.get("reason").asString()));
        assertThat(reasons).containsExactlyInAnyOrderEntriesOf(Map.of(paused.toString(), "PAUSED", hidden.toString(), "HIDDEN",
                gone.toString(), "DISCONTINUED", warrantyStopped.toString(), "WARRANTY_UNAVAILABLE"));
        assertThat(orderCount()).isZero();
    }

    @Test
    @DisplayName("하나라도 재고가 모자라면 409 INSUFFICIENT_STOCK — 주문을 만들지 않고, 먼저 확보한 옵션도 되돌린다(재고 행 없음도 부족)")
    void shortageOfAnyOptionCreatesNothing() throws Exception {
        UUID enough = sellable(10);
        UUID scarce = sellable(1);
        UUID noRow = sellable(-1);
        cartLine(enough, false, 3);
        cartLine(scarce, false, 2);
        cartLine(noRow, false, 1);

        expectError(place(item(enough, 3, false), item(scarce, 2, false), item(noRow, 1, false)), 409, "INSUFFICIENT_STOCK")
                .andExpect(jsonPath("$.error.details.variantIds.length()").value(2));

        assertThat(reserved(enough)).as("커밋 경계에서 되돌아갔다").isZero();
        assertThat(reserved(scarce)).isZero();
        assertThat(orderCount()).isZero();
    }

    @Test
    @DisplayName("요청 검증 — 줄 없음 · 같은 줄 두 번 · 보증 줄의 보증가 없음 · 수량 0 · 51줄은 400, 바로 구매는 아직 400")
    void invalidRequestsAreRejected() throws Exception {
        UUID phone = sellable(10);
        cartLine(phone, false, 1);

        expectViolation(perform("""
                {"source":"CART","shipTo":%s}""".formatted(SHIP_TO)), "items");
        expectViolation(place(item(phone, 1, false), item(phone, 1, false)), "items");
        expectViolation(place(String.format("""
                {"variantId":"%s","quantity":1,"warranty":true,"expectedUnitPrice":1250000}""", phone)), "items.expectedWarrantyPrice");
        expectViolation(place(item(phone, 0, false)), "items[0].quantity");
        expectViolation(place(java.util.stream.IntStream.range(0, 51).mapToObj(i -> item(UUID.randomUUID(), 1, false)).toArray(String[]::new)),
                "items");
        expectViolation(perform("""
                {"source":"BUY_NOW","items":[%s],"shipTo":%s}""".formatted(item(phone, 1, false), SHIP_TO)), "source");
        assertThat(orderCount()).isZero();
    }

    @Test
    @DisplayName("두 회원이 마지막 재고를 동시에 주문하면 하나만 만들어지고 나머지는 409 — 확보는 총량을 넘지 않는다")
    void concurrentOrdersForLastUnitCreateOne() throws Exception {
        UUID phone = sellable(1);
        UUID other = fixtures.customer();
        cartLine(phone, false, 1);
        cartLineOf(other, phone, false, 1);

        List<Outcome<Integer>> outcomes = Concurrently.run(2, i -> () -> mockMvc.perform(post("/api/v1/orders")
                        .with(TestAuth.customer(i == 0 ? customerId : other)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"source":"CART","items":[%s],"shipTo":%s}""".formatted(item(phone, 1, false), SHIP_TO)))
                .andReturn().getResponse().getStatus());

        assertThat(outcomes).extracting(Outcome::value).containsExactlyInAnyOrder(201, 409);
        assertThat(reserved(phone)).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 회원이 같은 구성을 동시에 두 번 보내면 주문 하나 · 확보 한 번 — 뒤의 요청은 장바구니 잠금 뒤에서 앞의 주문을 돌려받는다")
    void concurrentDoubleSubmitCreatesOneOrder() throws Exception {
        UUID phone = sellable(10);
        cartLine(phone, false, 2);

        List<Outcome<String>> outcomes = Concurrently.run(2, i -> () -> data(place(item(phone, 2, false))).get("orderId").asString());

        assertThat(outcomes).extracting(Outcome::value).doesNotContainNull().hasSize(2);
        assertThat(outcomes.get(0).value()).isEqualTo(outcomes.get(1).value());
        assertThat(orderCount()).isEqualTo(1);
        assertThat(reserved(phone)).isEqualTo(2);
    }

    /** 일반 판매 · 공개 · 판매 중 · 보증 제공 옵션. stock 이 음수면 재고 행을 만들지 않는다. */
    private UUID sellable(int stock) {
        OrderFixtures.StockProduct product = fixtures.inStockProduct(1);
        UUID option = product.optionIds().getFirst();
        if (stock >= 0) {
            fixtures.stock(option, stock, 0, 0);
        }
        catalog.put(option, new CatalogOption(option, product.productId(), "아이폰 17", "블랙 / 256GB", "SKU-" + option, PRICE,
                "ACTIVE", "IN_STOCK", "ACTIVE", true, true, new CatalogOption.Warranty(true, WARRANTY), null));
        return option;
    }

    private static CatalogOption copy(CatalogOption o, String productStatus, String optionStatus, boolean visible, boolean warrantyOffered) {
        return new CatalogOption(o.optionId(), o.productId(), o.productTitle(), o.optionTitle(), o.sku(), o.price(), optionStatus,
                o.saleMode(), productStatus, visible, true,
                new CatalogOption.Warranty(warrantyOffered, warrantyOffered ? WARRANTY : BigDecimal.ZERO), o.imageUrl());
    }

    private void cartLine(UUID option, boolean warranty, int quantity) {
        cartLineOf(customerId, option, warranty, quantity);
    }

    private void cartLineOf(UUID customer, UUID option, boolean warranty, int quantity) {
        jdbcTemplate.update("""
                INSERT INTO cart_items (id, customer_id, option_id, warranty_selected, quantity, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, bytes(UUID.randomUUID()), bytes(customer), bytes(option), warranty, quantity);
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
                {"source":"CART","items":[%s],"shipTo":%s}""".formatted(String.join(",", items), SHIP_TO));
    }

    private ResultActions perform(String body) throws Exception {
        return mockMvc.perform(post("/api/v1/orders").with(TestAuth.customer(customerId))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private int reserved(UUID option) {
        List<Integer> rows = jdbcTemplate.queryForList("SELECT stock_reserved FROM option_inventories WHERE option_id = ?", Integer.class,
                (Object) bytes(option));
        return rows.isEmpty() ? 0 : rows.getFirst();
    }

    private long orderCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM orders WHERE customer_id = ?", Long.class, (Object) bytes(customerId));
    }

    private List<Integer> cartQuantities() {
        return jdbcTemplate.queryForList("SELECT quantity FROM cart_items WHERE customer_id = ?", Integer.class, (Object) bytes(customerId));
    }

    private static Map<String, JsonNode> byVariant(JsonNode items) {
        Map<String, JsonNode> byVariant = new HashMap<>();
        items.forEach(item -> byVariant.put(item.get("optionId").asString(), item));
        return byVariant;
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
