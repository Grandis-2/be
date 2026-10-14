package com.grandis.nova.catalog.api;

import com.grandis.nova.catalog.support.CatalogIntegrationTest;
import com.grandis.nova.catalog.support.ShopFixtures;
import com.grandis.nova.common.UuidBinary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 마감된 사전예약 → 같은 상품 그대로 일반 상품(POST /admin/products/{id}/conversion). */
@CatalogIntegrationTest
@AutoConfigureMockMvc
class InStockConversionApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PATH = "/api/v1/admin/products";
    private static final Duration HOUR = Duration.ofHours(1);

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;

    ShopFixtures fixtures;
    UUID categoryId;

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
        categoryId = fixtures.category();
    }

    @Test
    @DisplayName("마감 뒤 전환 — 판매 방식 IN_STOCK · 판매 중 · 받은 할인율, 옵션 전부의 재고로 IN_STOCK_PRODUCT_REGISTERED 와 PREORDER_PRODUCT_CHANGED 를 남긴다")
    void convertsAfterClose() throws Exception {
        UUID productId = closedPreorder();
        Map<String, UUID> variants = variants(productId);
        long changedBefore = events(productId, "PREORDER_PRODUCT_CHANGED");

        JsonNode product = data(convert(productId, """
                { "discountRate": 10, "stocks": [ { "variantId": "%s", "stock": 5 }, { "variantId": "%s", "stock": 0 } ] }
                """.formatted(variants.get("256GB"), variants.get("512GB"))).andExpect(status().isOk())).get("product");

        assertThat(product.get("saleMode").asString()).isEqualTo("IN_STOCK");
        assertThat(product.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(product.get("discountRate").asInt()).isEqualTo(10);
        assertThat(product.get("campaign").isNull()).isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT sale_mode FROM products WHERE id = ?", String.class, (Object) UuidBinary.toBytes(productId)))
                .isEqualTo("IN_STOCK");

        JsonNode registered = JSON.readTree(jdbcTemplate.queryForObject(
                "SELECT payload FROM catalog_outbox_events WHERE aggregate_id = ? AND event_type = 'IN_STOCK_PRODUCT_REGISTERED'",
                String.class, (Object) UuidBinary.toBytes(productId)));
        Map<String, Integer> stocks = new LinkedHashMap<>();
        registered.get("items").forEach(item -> stocks.put(item.get("optionId").asString(), item.get("stockTotal").asInt()));
        assertThat(stocks).containsOnly(Map.entry(variants.get("256GB").toString(), 5), Map.entry(variants.get("512GB").toString(), 0));
        assertThat(events(productId, "PREORDER_PRODUCT_CHANGED")).isEqualTo(changedBefore + 1);
    }

    @Test
    @DisplayName("전환 뒤 order 가 재고를 만들면 회원 목록 · 상세 · 내부 조회에 일반 상품으로 — 회차 시각 · 접수 단계는 싣지 않는다")
    void convertedProductIsServedAsInStock() throws Exception {
        String tag = "cv" + ShopFixtures.unique();
        UUID productId = closedPreorder(tag);
        Map<String, UUID> variants = variants(productId);
        convert(productId, stocksBody(variants, 3)).andExpect(status().isOk());
        variants.values().forEach(optionId -> fixtures.inventory(optionId, 3, 0, 0));   // order 가 이벤트로 만든 재고

        JsonNode detail = data(mockMvc.perform(get("/api/v1/products/{id}", productId)).andExpect(status().isOk()));
        assertThat(detail.get("saleMode").asString()).isEqualTo("IN_STOCK");
        assertThat(detail.get("campaign").isNull()).isTrue();
        assertThat(detail.get("soldOut").asBoolean()).isFalse();
        detail.get("variants").forEach(variant -> assertThat(variant.get("availableQuantity").asInt()).isEqualTo(3));

        JsonNode item = data(mockMvc.perform(get("/api/v1/products").param("q", tag)).andExpect(status().isOk())).get("items").get(0);
        assertThat(item.get("saleMode").asString()).isEqualTo("IN_STOCK");
        assertThat(item.get("preorderStatus").isNull()).isTrue();
        assertThat(item.get("opensAt").isNull()).isTrue();
        assertThat(item.get("closesAt").isNull()).isTrue();

        mockMvc.perform(get("/internal/options").param("ids", variants.get("256GB").toString()).with(user("657").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].saleMode").value("IN_STOCK"));
    }

    @Test
    @DisplayName("오픈 전에 판매 중지로 둔 채 마감된 상품도 전환하면 판매 중이 된다 · 전환 뒤에는 오픈 뒤 수정 동결이 풀린다")
    void pausedBecomesActiveAndEditsReopen() throws Exception {
        UUID productId = closedPreorder();
        jdbcTemplate.update("UPDATE products SET status = 'PAUSED' WHERE id = ?", (Object) UuidBinary.toBytes(productId));
        admin(patch(PATH + "/{id}", productId), "{ \"title\": \"전환 전\" }").andExpect(status().isConflict());

        JsonNode product = data(convert(productId, stocksBody(variants(productId), 1)).andExpect(status().isOk())).get("product");
        assertThat(product.get("status").asString()).isEqualTo("ACTIVE");

        admin(patch(PATH + "/{id}", productId), "{ \"title\": \"전환 뒤\" }").andExpect(status().isOk());
    }

    @Test
    @DisplayName("마감 전 · 회차 없음 · 회차 취소 · 이미 일반 상품은 409 — 아무것도 바뀌지 않고 이벤트도 없다")
    void rejectsWhenNotConvertible() throws Exception {
        Instant now = Instant.now();
        UUID open = registerPreorder();
        moveCampaign(open, now.minus(HOUR), now.plus(HOUR));
        UUID notOpened = registerPreorder();
        UUID noCampaign = registerPreorder(false);
        UUID canceled = closedPreorder();
        jdbcTemplate.update("UPDATE products SET status = 'PAUSED', campaign_canceled_at = UTC_TIMESTAMP(6) WHERE id = ?",
                (Object) UuidBinary.toBytes(canceled));
        UUID converted = closedPreorder();
        convert(converted, stocksBody(variants(converted), 1)).andExpect(status().isOk());

        for (UUID productId : List.of(open, notOpened, noCampaign, canceled, converted)) {
            long before = events(productId, "IN_STOCK_PRODUCT_REGISTERED");
            convert(productId, stocksBody(variants(productId), 1))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("STATE_CONFLICT"));
            assertThat(events(productId, "IN_STOCK_PRODUCT_REGISTERED")).isEqualTo(before);
        }
        for (UUID productId : List.of(open, notOpened, noCampaign, canceled)) {
            assertThat(jdbcTemplate.queryForObject("SELECT sale_mode FROM products WHERE id = ?", String.class, (Object) UuidBinary.toBytes(productId)))
                    .isEqualTo("PREORDER");
        }
        convert(UUID.randomUUID(), "{ \"stocks\": [ { \"variantId\": \"%s\", \"stock\": 1 } ] }".formatted(UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("재고는 그 상품의 옵션을 빠짐없이 한 번씩 · 0 이상 정수 — 빠짐 · 모르는 옵션 · 두 번 · 음수 · 소수 · 빈 목록은 400 이고 바뀌지 않는다")
    void stocksMustCoverEveryOptionOnce() throws Exception {
        UUID productId = closedPreorder();
        Map<String, UUID> variants = variants(productId);
        UUID a = variants.get("256GB");
        UUID b = variants.get("512GB");
        String one = "{ \"variantId\": \"%s\", \"stock\": %s }";

        expectValidation(convert(productId, "{ \"stocks\": [ " + one.formatted(a, 1) + " ] }"), "stocks");
        expectValidation(convert(productId, "{ \"stocks\": [ " + one.formatted(UUID.randomUUID(), 1) + ", " + one.formatted(a, 1) + " ] }"),
                "stocks[0].variantId");
        expectValidation(convert(productId, "{ \"stocks\": [ " + one.formatted(a, 1) + ", " + one.formatted(a, 2) + " ] }"), "stocks[1].variantId");
        expectValidation(convert(productId, "{ \"stocks\": [ " + one.formatted(a, -1) + ", " + one.formatted(b, 1) + " ] }"), "stocks[0].stock");
        expectValidation(convert(productId, "{ \"stocks\": [ " + one.formatted(a, "1.5") + ", " + one.formatted(b, 1) + " ] }"), "stocks[0].stock");
        expectValidation(convert(productId, "{ \"stocks\": [] }"), "stocks");
        expectValidation(convert(productId, "{ \"discountRate\": 10 }"), "stocks");

        assertThat(jdbcTemplate.queryForObject("SELECT sale_mode FROM products WHERE id = ?", String.class, (Object) UuidBinary.toBytes(productId)))
                .isEqualTo("PREORDER");
        assertThat(events(productId, "IN_STOCK_PRODUCT_REGISTERED")).isZero();
    }

    @Test
    @DisplayName("할인율 — 비우면 0(사전예약 할인율을 잇지 않는다), 0~99 밖이면 400, 할인가가 100원보다 작아지면 400(discountRate)")
    void discountRateRules() throws Exception {
        UUID kept = closedPreorder();
        jdbcTemplate.update("UPDATE products SET discount_rate = 30 WHERE id = ?", (Object) UuidBinary.toBytes(kept));
        JsonNode product = data(convert(kept, stocksBody(variants(kept), 1)).andExpect(status().isOk())).get("product");
        assertThat(product.get("discountRate").asInt()).isZero();

        UUID other = closedPreorder();
        Map<String, UUID> variants = variants(other);
        expectValidation(convert(other, stocksBody(variants, 1).replace("{ \"stocks\"", "{ \"discountRate\": 100, \"stocks\"")), "discountRate");
        // 기본가 1,000,000 · 99% → 10,000 이라 하한에 안 걸린다 — 기본가를 낮춰 하한을 친다
        jdbcTemplate.update("UPDATE product_options SET price = 5000 WHERE product_id = ?", (Object) UuidBinary.toBytes(other));
        expectValidation(convert(other, stocksBody(variants, 1).replace("{ \"stocks\"", "{ \"discountRate\": 99, \"stocks\"")), "discountRate");
        assertThat(jdbcTemplate.queryForObject("SELECT sale_mode FROM products WHERE id = ?", String.class, (Object) UuidBinary.toBytes(other)))
                .isEqualTo("PREORDER");
    }

    @Test
    @DisplayName("관리자만 — 회원 403 · 익명 401")
    void adminOnly() throws Exception {
        UUID productId = closedPreorder();
        String body = stocksBody(variants(productId), 1);
        mockMvc.perform(post(PATH + "/{id}/conversion", productId).contentType(MediaType.APPLICATION_JSON).content(body)
                .with(user("657").roles("USER"))).andExpect(status().isForbidden());
        mockMvc.perform(post(PATH + "/{id}/conversion", productId).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
    }

    // ── 도우미 ──────────────────────────────────────────────────────────────

    private UUID closedPreorder() throws Exception {
        return closedPreorder("t" + ShopFixtures.unique());
    }

    /** 용량 축(256GB · 512GB +200,000) 사전예약을 등록하고 회차를 2시간 전 오픈 · 1시간 전 마감으로 옮긴다. */
    private UUID closedPreorder(String tags) throws Exception {
        UUID productId = registerPreorder(true, tags);
        Instant now = Instant.now();
        moveCampaign(productId, now.minus(HOUR.multipliedBy(2)), now.minus(HOUR));
        return productId;
    }

    private UUID registerPreorder() throws Exception {
        return registerPreorder(true);
    }

    private UUID registerPreorder(boolean withCampaign) throws Exception {
        return registerPreorder(withCampaign, "t" + ShopFixtures.unique());
    }

    private UUID registerPreorder(boolean withCampaign, String tags) throws Exception {
        Instant opensAt = Instant.now().plus(HOUR.multipliedBy(2));
        String body = """
                {
                  "categoryId": "%s", "saleMode": "PREORDER", "title": "Nova 1", "brand": "APPLE", "modelName": "A0001", "tags": "%s", "visible": true,
                  "basePrice": 1000000,
                  "optionAxes": [ { "key": "storage", "label": "용량", "values": [ { "value": "256GB" }, { "value": "512GB", "surcharge": 200000 } ] } ],
                  "campaign": { "opensAt": "%s", "closesAt": "%s" },
                  "shipmentBatches": [ { "batchNumber": 1, "positionFrom": 1, "positionTo": null, "estimatedShipStart": "2026-11-01", "estimatedShipEnd": "2026-11-07" } ],
                  "images": { "gallery": [ { "items": [ { "url": "https://img/n.jpg" } ] } ] }
                }
                """.formatted(categoryId, tags, opensAt, opensAt.plus(Duration.ofDays(3)));
        UUID productId = UUID.fromString(data(admin(post(PATH).header("Idempotency-Key", "k-" + ShopFixtures.unique()), body)
                .andExpect(status().isCreated())).get("registration").get("productId").asString());
        if (withCampaign) {
            fixtures.campaign(productId, opensAt, opensAt.plus(Duration.ofDays(3)));   // preorder 가 등록 이벤트로 만든 회차
        }
        return productId;
    }

    private void moveCampaign(UUID productId, Instant opensAt, Instant closesAt) {
        jdbcTemplate.update("UPDATE preorder_campaigns SET opens_at = ?, closes_at = ? WHERE product_id = ?",
                java.time.LocalDateTime.ofInstant(opensAt, java.time.ZoneOffset.UTC), java.time.LocalDateTime.ofInstant(closesAt, java.time.ZoneOffset.UTC),
                UuidBinary.toBytes(productId));
    }

    /** 용량 → 옵션 id. 관리자 상세에서 읽는다. */
    private Map<String, UUID> variants(UUID productId) throws Exception {
        Map<String, UUID> byStorage = new LinkedHashMap<>();
        JsonNode detail = data(mockMvc.perform(get(PATH + "/{id}", productId).with(user("admin").roles("ADMIN"))).andExpect(status().isOk()));
        detail.get("product").get("variants").forEach(variant ->
                byStorage.put(variant.get("selections").get("storage").asString(), UUID.fromString(variant.get("variantId").asString())));
        return byStorage;
    }

    private static String stocksBody(Map<String, UUID> variants, int stock) {
        List<String> items = new ArrayList<>();
        variants.values().forEach(id -> items.add("{ \"variantId\": \"%s\", \"stock\": %d }".formatted(id, stock)));
        return "{ \"stocks\": [ " + String.join(", ", items) + " ] }";
    }

    private ResultActions convert(UUID productId, String body) throws Exception {
        return admin(post(PATH + "/{id}/conversion", productId), body);
    }

    private ResultActions admin(MockHttpServletRequestBuilder request, String body) throws Exception {
        return mockMvc.perform(request.contentType(MediaType.APPLICATION_JSON).content(body).with(user("admin").roles("ADMIN")));
    }

    private long events(UUID productId, String type) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM catalog_outbox_events WHERE aggregate_id = ? AND event_type = ?",
                Long.class, UuidBinary.toBytes(productId), type);
    }

    private static void expectValidation(ResultActions actions, String field) throws Exception {
        actions.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details.violations[0].field").value(field));
    }

    private static JsonNode data(ResultActions actions) throws Exception {
        return JSON.readTree(actions.andReturn().getResponse().getContentAsString()).get("data");
    }
}
