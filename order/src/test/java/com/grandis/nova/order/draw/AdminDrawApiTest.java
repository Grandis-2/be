package com.grandis.nova.order.draw;

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
import java.util.Map;
import java.util.UUID;

import static com.grandis.nova.order.support.OrderFixtures.bytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 관리자 럭키 드로우 회차(만들기 · 목록 · 상세). catalog 는 대역, 재고는 표에 직접 심는다.
 * 대역 구성은 CartApiTest 와 같다 — Spring 컨텍스트를 함께 쓴다.
 */
@OrderIntegrationTest
@AutoConfigureMockMvc
class AdminDrawApiTest {

    static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @MockitoBean CatalogClient catalogClient;
    /** 쓰지 않지만 CartApiTest 와 같은 대역 구성으로 두어 Spring 컨텍스트를 함께 쓴다. */
    @MockitoSpyBean CartStore store;

    OrderFixtures fixtures;
    Map<UUID, CatalogOption> catalog = new HashMap<>();

    @BeforeEach
    void setUp() {
        fixtures = new OrderFixtures(jdbcTemplate);
        given(catalogClient.getOptions(any(), any())).willAnswer(invocation -> {
            Collection<UUID> ids = invocation.getArgument(0);
            return ApiResponse.ok(new CatalogOptions(ids.stream().filter(catalog::containsKey).map(catalog::get).toList()));
        });
    }

    @Test
    @DisplayName("비공개 증정품으로도 만든다 — 201 · Location, 당첨 인원만큼 재고 확보, 스냅샷 · 단계(응모 중)")
    void createsDrawReservingStockForWinners() throws Exception {
        UUID option = gift(10, false);
        Instant opens = Instant.now().minusSeconds(60);
        Instant closes = Instant.now().plus(Duration.ofDays(3));

        JsonNode draw = data(create(body(option, 3, opens, closes)).andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/api/v1/admin/draws/"))));

        assertThat(draw.get("title").asString()).isEqualTo("한정판 드로우");
        assertThat(draw.get("gift").get("variantId").asString()).isEqualTo(option.toString());
        assertThat(draw.get("gift").get("productTitle").asString()).isEqualTo("콜라보 굿즈");
        assertThat(draw.get("entryFee").decimalValue()).isEqualByComparingTo("100");
        assertThat(draw.get("winnerCount").asInt()).isEqualTo(3);
        assertThat(draw.get("phase").asString()).isEqualTo("OPEN");
        assertThat(reserved(option)).isEqualTo(3);

        JsonNode detail = data(mockMvc.perform(get("/api/v1/admin/draws/{id}", draw.get("drawId").asString()).with(TestAuth.admin()))
                .andExpect(status().isOk()));
        assertThat(detail.get("gift").get("optionTitle").asString()).isEqualTo("블랙");
    }

    @Test
    @DisplayName("당첨 인원만큼 재고가 없으면 409 INSUFFICIENT_STOCK — 회차 · 확보 없음")
    void insufficientStockCreatesNothing() throws Exception {
        UUID option = gift(2, true);

        create(body(option, 3, Instant.now(), Instant.now().plus(Duration.ofDays(1))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("INSUFFICIENT_STOCK"));

        assertThat(reserved(option)).isZero();
        assertThat(drawsOf(option)).isZero();
    }

    @Test
    @DisplayName("증정품 조건 — 없는 옵션 · 상품과 짝이 다르면 404, 사전예약 · 판매 중지 · 재고 미등록은 400(optionId)")
    void giftMustBeAnActiveStockedOption() throws Exception {
        Instant closes = Instant.now().plus(Duration.ofDays(1));
        create(body(UUID.randomUUID(), UUID.randomUUID(), 1, Instant.now(), closes)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PRODUCT_NOT_FOUND"));
        UUID option = gift(10, true);
        create(body(UUID.randomUUID(), option, 1, Instant.now(), closes)).andExpect(status().isNotFound());

        UUID preorder = gift(10, true);
        catalog.put(preorder, with(catalog.get(preorder), "PREORDER", "ACTIVE", "ACTIVE", true));
        expectViolation(create(body(preorder, 1, Instant.now(), closes)), "optionId");
        UUID paused = gift(10, true);
        catalog.put(paused, with(catalog.get(paused), "IN_STOCK", "ACTIVE", "PAUSED", true));
        expectViolation(create(body(paused, 1, Instant.now(), closes)), "optionId");
        UUID unregistered = gift(10, true);
        catalog.put(unregistered, with(catalog.get(unregistered), "IN_STOCK", "ACTIVE", "ACTIVE", false));
        expectViolation(create(body(unregistered, 1, Instant.now(), closes)), "optionId");
        assertThat(reserved(preorder) + reserved(paused) + reserved(unregistered)).isZero();
    }

    @Test
    @DisplayName("입력 검증 — 마감이 지났거나 시작보다 앞 · 응모비 0 · 당첨 0명 · 제목 없음은 400")
    void invalidInputIsRejected() throws Exception {
        UUID option = gift(10, true);
        Instant now = Instant.now();
        expectViolation(create(body(option, 1, now.minusSeconds(120), now.minusSeconds(60))), "closesAt");
        expectViolation(create(body(option, 1, now.plusSeconds(120), now.plusSeconds(60))), "closesAt");
        expectViolation(create(raw(option, "\"\"", "100", 1, now, now.plusSeconds(600))), "title");
        expectViolation(create(raw(option, "\"t\"", "0", 1, now, now.plusSeconds(600))), "entryFee");
        expectViolation(create(raw(option, "\"t\"", "100.5", 1, now, now.plusSeconds(600))), "entryFee");
        expectViolation(create(raw(option, "\"t\"", "100", 0, now, now.plusSeconds(600))), "winnerCount");
        assertThat(reserved(option)).isZero();
    }

    @Test
    @DisplayName("목록은 최신순 · 전체 건수, 단계는 시각으로 — 시작 전 SCHEDULED. 없는 회차 404")
    void listsNewestFirstWithPhase() throws Exception {
        UUID first = gift(10, true);
        UUID second = gift(10, true);
        create(body(first, 1, Instant.now(), Instant.now().plus(Duration.ofDays(1)))).andExpect(status().isCreated());
        String later = data(create(body(second, 1, Instant.now().plus(Duration.ofDays(1)), Instant.now().plus(Duration.ofDays(2))))
                .andExpect(status().isCreated())).get("drawId").asString();

        JsonNode page = data(mockMvc.perform(get("/api/v1/admin/draws").param("size", "1").with(TestAuth.admin())).andExpect(status().isOk()));
        assertThat(page.get("items").get(0).get("drawId").asString()).isEqualTo(later);
        assertThat(page.get("items").get(0).get("phase").asString()).isEqualTo("SCHEDULED");
        assertThat(page.get("total").asLong()).isGreaterThanOrEqualTo(2);
        mockMvc.perform(get("/api/v1/admin/draws").param("size", "0").with(TestAuth.admin())).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/admin/draws/{id}", UUID.randomUUID()).with(TestAuth.admin()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("DRAW_NOT_FOUND"));
    }

    @Test
    @DisplayName("관리자만 — 익명 401, 회원 403")
    void adminOnly() throws Exception {
        mockMvc.perform(get("/api/v1/admin/draws")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/admin/draws").with(TestAuth.customer(UUID.randomUUID()))).andExpect(status().isForbidden());
    }

    /** 일반 판매 · 판매 중 · 재고 등록 옵션. visible=false 면 매장에 안 보이는 증정품이다. */
    private UUID gift(int stock, boolean visible) {
        OrderFixtures.StockProduct product = fixtures.inStockProduct(1);
        UUID option = product.optionIds().getFirst();
        fixtures.stock(option, stock, 0, 0);
        catalog.put(option, new CatalogOption(option, product.productId(), "콜라보 굿즈", "블랙", "SKU-" + option, new BigDecimal("50000"),
                "ACTIVE", "IN_STOCK", "ACTIVE", visible, true, new CatalogOption.Warranty(false, BigDecimal.ZERO), "https://img/gift.jpg"));
        return option;
    }

    private static CatalogOption with(CatalogOption o, String saleMode, String productStatus, String optionStatus, boolean registered) {
        return new CatalogOption(o.optionId(), o.productId(), o.productTitle(), o.optionTitle(), o.sku(), o.price(), optionStatus, saleMode,
                productStatus, o.visible(), registered, o.warranty(), o.imageUrl());
    }

    private String body(UUID option, int winners, Instant opens, Instant closes) {
        return body(catalog.get(option).productId(), option, winners, opens, closes);
    }

    private static String body(UUID product, UUID option, int winners, Instant opens, Instant closes) {
        return """
                {"productId":"%s","optionId":"%s","title":"한정판 드로우","entryFee":100,"winnerCount":%d,"opensAt":"%s","closesAt":"%s"}"""
                .formatted(product, option, winners, opens, closes);
    }

    private String raw(UUID option, String title, String fee, int winners, Instant opens, Instant closes) {
        return """
                {"productId":"%s","optionId":"%s","title":%s,"entryFee":%s,"winnerCount":%d,"opensAt":"%s","closesAt":"%s"}"""
                .formatted(catalog.get(option).productId(), option, title, fee, winners, opens, closes);
    }

    private ResultActions create(String body) throws Exception {
        return mockMvc.perform(post("/api/v1/admin/draws").with(TestAuth.admin()).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private int reserved(UUID option) {
        return jdbcTemplate.queryForObject("SELECT stock_reserved FROM option_inventories WHERE option_id = ?", Integer.class,
                (Object) bytes(option));
    }

    private long drawsOf(UUID option) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM draw_campaigns WHERE option_id = ?", Long.class, (Object) bytes(option));
    }

    private static JsonNode data(ResultActions actions) throws Exception {
        return JSON.readTree(actions.andReturn().getResponse().getContentAsString()).get("data");
    }

    private static void expectViolation(ResultActions actions, String field) throws Exception {
        actions.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details.violations[0].field").value(field));
    }
}
