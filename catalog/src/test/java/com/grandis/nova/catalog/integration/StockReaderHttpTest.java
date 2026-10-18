package com.grandis.nova.catalog.integration;

import com.grandis.nova.catalog.support.AccessTokens;
import com.grandis.nova.catalog.support.CatalogIntegrationTest;
import com.grandis.nova.catalog.support.ShopFixtures;
import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * order 재고 API 를 진짜 HTTP 로 부른다 — order 주소를 이 시험이 띄운 HTTP 서버로 돌린다. 재고 그룹(order-stocks)은 주소를 따로 주지 않는다:
 * 코드 기본값이 order 그룹 주소를 따른다. 시험용 재고 대역은 끈다(nova.test.fake-order-stocks=false).
 */
@CatalogIntegrationTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "nova.test.fake-order-stocks=false")
@DisplayName("order 재고 API — 토큰 없이 부르고, 100개씩 나누고, 장애면 목록 품절 · 상세 수량을 비운다(실제 HTTP)")
class StockReaderHttpTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final List<String> SEEN_AUTHORIZATION = new CopyOnWriteArrayList<>();
    private static final List<List<String>> SEEN_IDS = new CopyOnWriteArrayList<>();
    /** 옵션 id → 남은 수량. 없으면 재고 행 없음(응답에서 빠짐). */
    private static final Map<String, Integer> STOCK = new ConcurrentHashMap<>();
    private static volatile int failStatus;
    private static volatile Function<List<String>, String> override;
    private static final HttpServer SERVER = start();

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired StockReader reader;

    ShopFixtures fixtures;

    @DynamicPropertySource
    static void order(DynamicPropertyRegistry registry) {
        registry.add("spring.http.serviceclient.order.base-url", () -> "http://localhost:" + SERVER.getAddress().getPort());
    }

    @AfterAll
    static void stop() {
        SERVER.stop(0);
    }

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
        SEEN_AUTHORIZATION.clear();
        SEEN_IDS.clear();
        STOCK.clear();
        failStatus = 0;
        override = null;
    }

    @Test
    @DisplayName("상세의 옵션 수량은 order 답 그대로, 재고 행이 없는 옵션은 0 — 회원 토큰이 실린 요청이어도 order 에는 토큰을 싣지 않는다")
    void detailReadsStocksWithoutToken() throws Exception {
        UUID product = readyProduct();
        UUID stocked = fixtures.option(product, "ACTIVE", ShopFixtures.OPTION_PRICE);
        UUID noRow = fixtures.option(product, "ACTIVE", ShopFixtures.OPTION_PRICE);
        STOCK.put(stocked.toString(), 7);

        JsonNode detail = detail(product, true);

        assertThat(quantity(detail, stocked)).isEqualTo(7);
        assertThat(quantity(detail, noRow)).isEqualTo(0);
        assertThat(detail.get("soldOut").asBoolean()).isFalse();
        assertThat(SEEN_AUTHORIZATION).isNotEmpty().allMatch("null"::equals);
    }

    @Test
    @DisplayName("order 가 503 이면 상세는 200 · 옵션 수량 null · 품절 아님, 목록은 품절 표시만 뺀다")
    void orderDownLeavesQuantityUnknown() throws Exception {
        UUID product = readyProduct();
        UUID option = fixtures.option(product, "ACTIVE", ShopFixtures.OPTION_PRICE);
        failStatus = 503;

        JsonNode detail = detail(product, false);
        assertThat(detail.get("variants").get(0).get("availableQuantity").isNull()).isTrue();
        assertThat(detail.get("soldOut").asBoolean()).isFalse();

        failStatus = 0;
        assertThat(soldOutInList(product)).as("대조군 — order 가 답하면 재고 0 이라 품절").isTrue();
        failStatus = 503;
        assertThat(soldOutInList(product)).as("장애면 품절 표시를 뺀다").isFalse();
        assertThat(option).isNotNull();
    }

    @Test
    @DisplayName("100개씩 나눠 부른다 — 250개면 100 · 100 · 50")
    void asksInBatchesOfHundred() {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 250; i++) {
            ids.add(UUID.randomUUID());
        }

        assertThat(reader.available(ids)).isEmpty();
        assertThat(SEEN_IDS).extracting(List::size).containsExactly(100, 100, 50);
    }

    @Test
    @DisplayName("묻지 않은 옵션 · 빈 수량이 오면 연동 오류(500), 그 밖의 4xx 도 연동 오류, 5xx 는 일시 장애(503)")
    void contractViolationsAndFailures() {
        UUID asked = UUID.randomUUID();
        override = requested -> "{\"success\":true,\"data\":{\"items\":[{\"optionId\":\"%s\",\"available\":1}]}}".formatted(UUID.randomUUID());
        assertThatThrownBy(() -> reader.available(List.of(asked))).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode()).isEqualTo(CommonErrorCode.INTERNAL_ERROR));
        override = requested -> "{\"success\":true,\"data\":{\"items\":[{\"optionId\":\"%s\",\"available\":null}]}}".formatted(asked);
        assertThatThrownBy(() -> reader.available(List.of(asked))).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode()).isEqualTo(CommonErrorCode.INTERNAL_ERROR));
        override = null;
        failStatus = 404;
        assertThatThrownBy(() -> reader.available(List.of(asked))).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode()).isEqualTo(CommonErrorCode.INTERNAL_ERROR));
        failStatus = 500;
        assertThatThrownBy(() -> reader.available(List.of(asked))).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode()).isEqualTo(CommonErrorCode.DEPENDENCY_UNAVAILABLE));
    }

    // ── 도우미 ──

    private UUID readyProduct() {
        UUID product = fixtures.product("IN_STOCK", "ACTIVE");
        fixtures.markStockReady(product);
        return product;
    }

    private JsonNode detail(UUID product, boolean withToken) throws Exception {
        var request = get("/api/v1/products/{id}", product);
        if (withToken) {
            request = request.with(AccessTokens.customer(UUID.randomUUID()));
        }
        return JSON.readTree(mockMvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("data");
    }

    private boolean soldOutInList(UUID product) throws Exception {
        String category = jdbcTemplate.queryForObject("SELECT BIN_TO_UUID(category_id) FROM products WHERE id = UUID_TO_BIN(?)", String.class, product.toString());
        JsonNode items = JSON.readTree(mockMvc.perform(get("/api/v1/products").param("categoryId", category)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("data").get("items");
        for (JsonNode item : items) {
            if (item.get("productId").asString().equals(product.toString())) {
                return item.get("soldOut").asBoolean();
            }
        }
        throw new AssertionError("목록에 상품이 없다: " + product);
    }

    private static int quantity(JsonNode detail, UUID option) {
        for (JsonNode variant : detail.get("variants")) {
            if (variant.get("variantId").asString().equals(option.toString())) {
                return variant.get("availableQuantity").asInt();
            }
        }
        throw new AssertionError("옵션이 없다: " + option);
    }

    private static HttpServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/internal/stocks", exchange -> {
                SEEN_AUTHORIZATION.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
                List<String> ids = new ArrayList<>();
                String query = exchange.getRequestURI().getRawQuery();
                for (String pair : query == null ? new String[0] : query.split("&")) {
                    String[] kv = pair.split("=", 2);
                    if (kv[0].equals("optionIds") && kv.length == 2) {
                        ids.addAll(Arrays.asList(URLDecoder.decode(kv[1], StandardCharsets.UTF_8).split(",")));
                    }
                }
                SEEN_IDS.add(ids);
                if (failStatus != 0) {
                    respond(exchange, failStatus, "{\"success\":false,\"data\":null,\"error\":{\"code\":\"X\",\"message\":\"x\",\"details\":null}}");
                    return;
                }
                if (override != null) {
                    respond(exchange, 200, override.apply(ids));
                    return;
                }
                StringBuilder items = new StringBuilder();
                for (String id : ids) {
                    Integer available = STOCK.get(id);
                    if (available != null) {
                        items.append(items.isEmpty() ? "" : ",").append("{\"optionId\":\"%s\",\"available\":%d}".formatted(id, available));
                    }
                }
                respond(exchange, 200, "{\"success\":true,\"data\":{\"items\":[" + items + "]}}");
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
