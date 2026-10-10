package com.grandis.nova.order.cart;

import com.grandis.nova.common.web.ApiResponse;
import com.grandis.nova.order.cart.domain.repository.CartStore;
import com.grandis.nova.order.client.catalog.CatalogClient;
import com.grandis.nova.order.client.catalog.CatalogReader;
import com.grandis.nova.order.client.catalog.CatalogOption;
import com.grandis.nova.order.client.catalog.CatalogOptions;
import com.grandis.nova.order.support.OrderFixtures;
import com.grandis.nova.order.support.OrderIntegrationTest;
import com.grandis.nova.order.support.TestAuth;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.grandis.nova.order.support.OrderFixtures.bytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 장바구니 API(명세 F-U-07). catalog 는 대역(CatalogClient)으로, 재고는 order 의 재고 표에 직접 심는다.
 */
@OrderIntegrationTest
@AutoConfigureMockMvc
class CartApiTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final BigDecimal PRICE = new BigDecimal("1250000");
    static final BigDecimal WARRANTY = new BigDecimal("199000");

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @MockitoBean CatalogClient catalogClient;
    /** 쓰지 않지만 CartConcurrencyTest 와 같은 대역 구성으로 두어 Spring 컨텍스트(와 MySQL 컨테이너)를 하나로 함께 쓴다. */
    @MockitoSpyBean CartStore store;
    @Autowired CircuitBreakerRegistry circuitBreakers;
    @Autowired BulkheadRegistry bulkheads;

    OrderFixtures fixtures;
    UUID customerId;
    /** catalog 가 아는 옵션들 — 대역은 물은 id 중 여기 있는 것만 돌려준다(없는 옵션은 빠진다). */
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
    @DisplayName("비어 있으면 catalog 를 부르지 않고 빈 목록, 개수 0")
    void emptyCart() throws Exception {
        JsonNode data = data(asCustomer(get("/api/v1/cart")).andExpect(status().isOk()));
        assertThat(data.get("items").size()).isZero();
        assertThat(data(asCustomer(get("/api/v1/cart/count"))).get("count").asLong()).isZero();
        verify(catalogClient, never()).getOptions(any(), any());
    }

    @Test
    @DisplayName("담으면 줄이 생기고, 같은 (옵션, 보증)은 합산 · 보증 포함은 다른 줄. 조회는 catalog 지금 값 · 재고로 금액과 살 수 있음을 단다")
    void addMergesSameSlotAndSeparatesWarranty() throws Exception {
        UUID option = sellable(true, 10);

        String first = data(add(option, 2, null).andExpect(status().isOk())).get("cartItemId").asString();
        JsonNode merged = data(add(option, 3, false).andExpect(status().isOk()));
        assertThat(merged.get("cartItemId").asString()).as("같은 줄에 합산").isEqualTo(first);
        assertThat(merged.get("quantity").asInt()).isEqualTo(5);
        JsonNode withWarranty = data(add(option, 1, true).andExpect(status().isOk()));
        assertThat(withWarranty.get("cartItemId").asString()).as("보증 포함은 다른 줄").isNotEqualTo(first);

        JsonNode items = data(asCustomer(get("/api/v1/cart")).andExpect(status().isOk())).get("items");
        assertThat(items.size()).isEqualTo(2);
        JsonNode plain = items.get(0);
        assertThat(plain.get("variantId").asString()).isEqualTo(option.toString());
        assertThat(plain.get("productTitle").asString()).isEqualTo("아이폰 17");
        assertThat(plain.get("warranty").asBoolean()).isFalse();
        assertThat(plain.get("quantity").asInt()).isEqualTo(5);
        assertThat(plain.get("unitPrice").decimalValue()).isEqualByComparingTo(PRICE);
        assertThat(plain.get("warrantyPrice").decimalValue()).isEqualByComparingTo("0");
        assertThat(plain.get("lineAmount").decimalValue()).isEqualByComparingTo(PRICE.multiply(BigDecimal.valueOf(5)));
        assertThat(plain.get("availableQuantity").asInt()).isEqualTo(10);
        assertThat(plain.get("purchasable").asBoolean()).isTrue();
        assertThat(plain.get("unavailableReason").isNull()).isTrue();
        JsonNode warranty = items.get(1);
        assertThat(warranty.get("warranty").asBoolean()).isTrue();
        assertThat(warranty.get("warrantyPrice").decimalValue()).isEqualByComparingTo(WARRANTY);
        assertThat(warranty.get("lineAmount").decimalValue()).isEqualByComparingTo(PRICE.add(WARRANTY));
        assertThat(data(asCustomer(get("/api/v1/cart/count"))).get("count").asLong()).isEqualTo(2);
    }

    @Test
    @DisplayName("담기 거절 — 없는 · 비공개 · 준비 전 404, 사전예약 409 PREORDER_NOT_CARTABLE, 판매 중지 409 STATE_CONFLICT, 보증 미제공에 보증 400, 재고 초과 409 INSUFFICIENT_STOCK")
    void addRejections() throws Exception {
        expectError(add(UUID.randomUUID(), 1, false), HttpStatus.NOT_FOUND, "NOT_FOUND");
        UUID hidden = sellable(true, 10);
        catalog.put(hidden, copy(catalog.get(hidden), "IN_STOCK", "ACTIVE", "ACTIVE", false, true, true));
        expectError(add(hidden, 1, false), HttpStatus.NOT_FOUND, "NOT_FOUND");
        UUID notReady = sellable(true, 10);
        catalog.put(notReady, copy(catalog.get(notReady), "IN_STOCK", "ACTIVE", "ACTIVE", true, false, true));
        expectError(add(notReady, 1, false), HttpStatus.NOT_FOUND, "NOT_FOUND");
        UUID preorder = sellable(true, 10);
        catalog.put(preorder, copy(catalog.get(preorder), "PREORDER", "ACTIVE", "ACTIVE", true, true, true));
        expectError(add(preorder, 1, false), HttpStatus.CONFLICT, "PREORDER_NOT_CARTABLE");
        UUID paused = sellable(true, 10);
        catalog.put(paused, copy(catalog.get(paused), "IN_STOCK", "ACTIVE", "PAUSED", true, true, true));
        expectError(add(paused, 1, false), HttpStatus.CONFLICT, "STATE_CONFLICT");
        UUID noWarranty = sellable(false, 10);
        expectViolation(add(noWarranty, 1, true), "warranty");
        UUID scarce = sellable(true, 3);
        add(scarce, 2, false).andExpect(status().isOk());
        expectError(add(scarce, 2, false), HttpStatus.CONFLICT, "INSUFFICIENT_STOCK");
        UUID noStockRow = sellable(true, -1);
        expectError(add(noStockRow, 1, false), HttpStatus.CONFLICT, "INSUFFICIENT_STOCK");
        assertThat(lineCount()).as("거절된 담기는 줄을 남기지 않는다").isEqualTo(1);
    }

    @Test
    @DisplayName("한 줄 수량은 1~99 — 요청 0 · 100 은 400, 합산이 99 를 넘어도 400. 51번째 줄은 400")
    void quantityAndLineLimits() throws Exception {
        UUID option = sellable(true, 1000);
        expectViolation(add(option, 0, false), "quantity");
        expectViolation(add(option, 100, false), "quantity");
        add(option, 98, false).andExpect(status().isOk());
        expectViolation(add(option, 2, false), "quantity");
        add(option, 1, false).andExpect(status().isOk());

        for (int i = 1; i < 50; i++) {
            add(sellable(false, 5), 1, false).andExpect(status().isOk());
        }
        assertThat(lineCount()).isEqualTo(50);
        expectViolation(add(sellable(false, 5), 1, false), "variantId");
        expectViolation(add(option, 1, true), "variantId");
        assertThat(lineCount()).as("50줄을 넘지 않는다").isEqualTo(50);
    }

    @Test
    @DisplayName("조회는 살 수 없는 줄도 지우지 않고 이유를 하나 단다 — 판매 종료 · 숨김 · 판매 중지 · 보증 중단 · 품절 · 재고 부족. 겹치면 앞의 것")
    void viewMarksUnavailableLines() throws Exception {
        UUID discontinued = sellable(true, 10);
        UUID hidden = sellable(true, 10);
        UUID paused = sellable(true, 10);
        UUID warrantyStopped = sellable(true, 10);
        UUID soldOut = sellable(true, 10);
        UUID scarce = sellable(true, 10);
        // 이유가 겹치는 줄 — 위에 있는 이유 하나만 보인다
        UUID hiddenAndPaused = sellable(true, 10);
        UUID nowPreorderAndPaused = sellable(true, 10);
        UUID pausedAndSoldOut = sellable(true, 10);
        UUID warrantyStoppedAndSoldOut = sellable(true, 10);
        UUID pausedAndWarrantyStopped = sellable(true, 10);
        UUID soldOutAndTooMany = sellable(true, 10);
        for (UUID option : List.of(discontinued, hidden, paused, soldOut, scarce, hiddenAndPaused, nowPreorderAndPaused, pausedAndSoldOut,
                soldOutAndTooMany)) {
            add(option, 3, false).andExpect(status().isOk());
        }
        add(warrantyStopped, 1, true).andExpect(status().isOk());
        add(warrantyStoppedAndSoldOut, 1, true).andExpect(status().isOk());
        add(pausedAndWarrantyStopped, 1, true).andExpect(status().isOk());

        catalog.remove(discontinued);
        catalog.put(hidden, copy(catalog.get(hidden), "IN_STOCK", "ACTIVE", "ACTIVE", false, true, true));
        catalog.put(paused, copy(catalog.get(paused), "IN_STOCK", "PAUSED", "ACTIVE", true, true, true));
        catalog.put(warrantyStopped, copy(catalog.get(warrantyStopped), "IN_STOCK", "ACTIVE", "ACTIVE", true, true, false));
        jdbcTemplate.update("UPDATE option_inventories SET stock_sold = stock_total WHERE option_id = ?", (Object) bytes(soldOut));
        jdbcTemplate.update("UPDATE option_inventories SET stock_reserved = 8 WHERE option_id = ?", (Object) bytes(scarce));
        catalog.put(hiddenAndPaused, copy(catalog.get(hiddenAndPaused), "IN_STOCK", "PAUSED", "ACTIVE", false, true, true));
        catalog.put(nowPreorderAndPaused, copy(catalog.get(nowPreorderAndPaused), "PREORDER", "ACTIVE", "PAUSED", true, true, true));
        catalog.put(pausedAndSoldOut, copy(catalog.get(pausedAndSoldOut), "IN_STOCK", "ACTIVE", "PAUSED", true, true, true));
        catalog.put(warrantyStoppedAndSoldOut, copy(catalog.get(warrantyStoppedAndSoldOut), "IN_STOCK", "ACTIVE", "ACTIVE", true, true, false));
        catalog.put(pausedAndWarrantyStopped, copy(catalog.get(pausedAndWarrantyStopped), "IN_STOCK", "PAUSED", "ACTIVE", true, true, false));
        for (UUID option : List.of(pausedAndSoldOut, warrantyStoppedAndSoldOut, soldOutAndTooMany)) {
            jdbcTemplate.update("UPDATE option_inventories SET stock_sold = stock_total WHERE option_id = ?", (Object) bytes(option));
        }

        Map<String, JsonNode> byVariant = new HashMap<>();
        data(asCustomer(get("/api/v1/cart")).andExpect(status().isOk())).get("items")
                .forEach(item -> byVariant.put(item.get("variantId").asString(), item));
        assertThat(byVariant).hasSize(12);
        expectReason(byVariant.get(discontinued.toString()), "DISCONTINUED");
        assertThat(byVariant.get(discontinued.toString()).get("productTitle").isNull()).as("없어진 옵션은 상품 칸이 비어 있다").isTrue();
        expectReason(byVariant.get(hidden.toString()), "HIDDEN");
        expectReason(byVariant.get(paused.toString()), "PAUSED");
        expectReason(byVariant.get(warrantyStopped.toString()), "WARRANTY_UNAVAILABLE");
        expectReason(byVariant.get(soldOut.toString()), "OUT_OF_STOCK");
        expectReason(byVariant.get(scarce.toString()), "INSUFFICIENT_STOCK");
        assertThat(byVariant.get(scarce.toString()).get("availableQuantity").asInt()).isEqualTo(2);
        expectReason(byVariant.get(hiddenAndPaused.toString()), "HIDDEN");
        expectReason(byVariant.get(nowPreorderAndPaused.toString()), "HIDDEN");
        expectReason(byVariant.get(pausedAndSoldOut.toString()), "PAUSED");
        expectReason(byVariant.get(warrantyStoppedAndSoldOut.toString()), "WARRANTY_UNAVAILABLE");
        expectReason(byVariant.get(pausedAndWarrantyStopped.toString()), "PAUSED");
        expectReason(byVariant.get(soldOutAndTooMany.toString()), "OUT_OF_STOCK");
    }

    @Test
    @DisplayName("수량 변경은 절대값 · 재고 안에서만 — 재고 초과 409, 0 · 100 400, 남의 줄 404. 삭제는 204, 남의 줄 · 없는 줄 404")
    void changeQuantityAndRemove() throws Exception {
        UUID option = sellable(true, 5);
        String line = data(add(option, 1, false)).get("cartItemId").asString();

        JsonNode changed = data(changeQuantity(line, 4).andExpect(status().isOk()));
        assertThat(changed.get("quantity").asInt()).isEqualTo(4);
        expectError(changeQuantity(line, 6),
                HttpStatus.CONFLICT, "INSUFFICIENT_STOCK");
        expectViolation(changeQuantity(line, 0), "quantity");
        expectViolation(changeQuantity(line, 100), "quantity");
        UUID stranger = fixtures.customer();
        expectError(mockMvc.perform(patch("/api/v1/cart/items/{id}", line).with(TestAuth.customer(stranger))
                .contentType(MediaType.APPLICATION_JSON).content("{ \"quantity\": 2 }")), HttpStatus.NOT_FOUND, "NOT_FOUND");
        expectError(mockMvc.perform(delete("/api/v1/cart/items/{id}", line).with(TestAuth.customer(stranger))), HttpStatus.NOT_FOUND, "NOT_FOUND");

        asCustomer(delete("/api/v1/cart/items/{id}", line)).andExpect(status().isNoContent());
        expectError(asCustomer(delete("/api/v1/cart/items/{id}", line)), HttpStatus.NOT_FOUND, "NOT_FOUND");
        assertThat(lineCount()).isZero();
    }

    @Test
    @DisplayName("회원만 — 익명 401, 관리자 403. catalog 가 답하지 않으면 503 이고 장바구니는 그대로")
    void authAndDependencyFailure() throws Exception {
        mockMvc.perform(get("/api/v1/cart")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/cart/count")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/cart/items").contentType(MediaType.APPLICATION_JSON)
                .content("{ \"variantId\": \"%s\", \"quantity\": 1 }".formatted(UUID.randomUUID()))).andExpect(status().isUnauthorized());
        mockMvc.perform(patch("/api/v1/cart/items/{id}", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .content("{ \"quantity\": 1 }")).andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/v1/cart/items/{id}", UUID.randomUUID())).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/cart").with(TestAuth.admin())).andExpect(status().isForbidden());

        UUID option = sellable(true, 5);
        add(option, 1, false).andExpect(status().isOk());
        willThrow(HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE, "Service Unavailable", null, null, null))
                .given(catalogClient).getOptions(any(), any());
        expectError(asCustomer(get("/api/v1/cart")), HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE");
        expectError(add(option, 1, false), HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE");
        assertThat(jdbcTemplate.queryForObject("SELECT quantity FROM cart_items WHERE customer_id = ?", Integer.class, (Object) bytes(customerId)))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("catalog 호출의 서킷 브레이커 · 동시 상한은 배포 기본값으로 선다 — 5xx 는 회로의 실패, 4xx · 상한 초과는 아니다")
    void catalogResilienceDefaultsAreApplied() throws Exception {
        CircuitBreaker breaker = circuitBreakers.circuitBreaker(CatalogReader.DEPENDENCY);
        breaker.reset();
        try {
            assertThat(breaker.getCircuitBreakerConfig().getMinimumNumberOfCalls()).isEqualTo(20);
            assertThat(breaker.getCircuitBreakerConfig().getWaitIntervalFunctionInOpenState().apply(1)).isEqualTo(10_000L);
            assertThat(bulkheads.bulkhead(CatalogReader.DEPENDENCY).getBulkheadConfig().getMaxConcurrentCalls()).isEqualTo(20);
            assertThat(bulkheads.bulkhead(CatalogReader.DEPENDENCY).getBulkheadConfig().getMaxWaitDuration()).isZero();

            UUID option = sellable(true, 5);
            willThrow(HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "Bad Request", null, null, null))
                    .given(catalogClient).getOptions(any(), any());
            add(option, 1, false).andExpect(status().isInternalServerError());
            assertThat(breaker.getMetrics().getNumberOfFailedCalls()).as("4xx 는 실패로 세지 않는다").isZero();

            willThrow(HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE, "Service Unavailable", null, null, null))
                    .given(catalogClient).getOptions(any(), any());
            add(option, 1, false).andExpect(status().isServiceUnavailable());
            assertThat(breaker.getMetrics().getNumberOfFailedCalls()).as("5xx 는 실패").isEqualTo(1);
        } finally {
            breaker.reset();   // 같은 컨텍스트를 쓰는 다른 시험에 회로 상태를 남기지 않는다
        }
    }

    /** 일반 판매 · 공개 · 판매 중인 옵션 하나. stock 이 음수면 재고 행을 만들지 않는다. */
    private UUID sellable(boolean warrantyOffered, int stock) {
        OrderFixtures.StockProduct product = fixtures.inStockProduct(1);
        UUID option = product.optionIds().getFirst();
        if (stock >= 0) {
            fixtures.stock(option, stock, 0, 0);
        }
        catalog.put(option, new CatalogOption(option, product.productId(), "아이폰 17", "블랙 / 256GB", "SKU-" + option, PRICE,
                "ACTIVE", "IN_STOCK", "ACTIVE", true, true,
                new CatalogOption.Warranty(warrantyOffered, warrantyOffered ? WARRANTY : BigDecimal.ZERO), "https://img/black-0.jpg"));
        return option;
    }

    private static CatalogOption copy(CatalogOption o, String saleMode, String productStatus, String optionStatus, boolean visible,
                                      boolean ready, boolean warrantyOffered) {
        return new CatalogOption(o.optionId(), o.productId(), o.productTitle(), o.optionTitle(), o.sku(), o.price(), optionStatus, saleMode,
                productStatus, visible, ready, new CatalogOption.Warranty(warrantyOffered, warrantyOffered ? WARRANTY : BigDecimal.ZERO), o.imageUrl());
    }

    private ResultActions add(UUID option, int quantity, Boolean warranty) throws Exception {
        String body = warranty == null ? "{ \"variantId\": \"%s\", \"quantity\": %d }".formatted(option, quantity)
                : "{ \"variantId\": \"%s\", \"quantity\": %d, \"warranty\": %s }".formatted(option, quantity, warranty);
        return asCustomer(post("/api/v1/cart/items").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions changeQuantity(String line, int quantity) throws Exception {
        return asCustomer(patch("/api/v1/cart/items/{id}", line).contentType(MediaType.APPLICATION_JSON)
                .content("{ \"quantity\": %d }".formatted(quantity)));
    }

    private ResultActions asCustomer(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.with(TestAuth.customer(customerId)));
    }

    private long lineCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cart_items WHERE customer_id = ?", Long.class, (Object) bytes(customerId));
    }

    private static JsonNode data(ResultActions actions) throws Exception {
        return JSON.readTree(actions.andReturn().getResponse().getContentAsString()).get("data");
    }

    private static void expectError(ResultActions actions, HttpStatus status, String code) throws Exception {
        actions.andExpect(status().is(status.value())).andExpect(jsonPath("$.error.code").value(code));
    }

    private static void expectViolation(ResultActions actions, String field) throws Exception {
        actions.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details.violations[0].field").value(field));
    }

    private static void expectReason(JsonNode item, String reason) {
        assertThat(item.get("purchasable").asBoolean()).as(reason).isFalse();
        assertThat(item.get("unavailableReason").asString()).isEqualTo(reason);
    }
}
