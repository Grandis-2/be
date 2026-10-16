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

/** 상품 할인율 — 등록 · 수정 · 회원 상세 · 목록 · 내부 조회(order · preorder 가 접수 · 주문 가격으로 복사하는 값). */
@CatalogIntegrationTest
@AutoConfigureMockMvc
class ProductDiscountApiTest {

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
    @DisplayName("등록의 할인율이 옵션마다 걸린다 — price 는 100원 단위로 버린 할인가, listPrice 는 정가, 보증 추가금은 할인하지 않는다")
    void registeredRateAppliesToEveryOption() throws Exception {
        UUID productId = registerInStock("\"discountRate\": 5,");
        fixtures.stockReady(productId);

        JsonNode product = data(mockMvc.perform(get("/api/v1/products/{id}", productId)).andExpect(status().isOk()));
        assertThat(product.get("discountRate").asInt()).isEqualTo(5);
        assertThat(product.get("warranty").get("surcharge").decimalValue()).isEqualByComparingTo("150000");
        // 256GB 2,278,190 × 0.95 = 2,164,280.5 · 512GB 2,478,190 × 0.95 = 2,354,280.5
        assertThat(prices(product)).containsExactly(Map.entry("256GB", "2164200/2278190"), Map.entry("512GB", "2354200/2478190"));
    }

    @Test
    @DisplayName("할인율을 비우면 0 — price 가 정가 그대로다(100원 미만도 버리지 않는다)")
    void omittedRateIsZero() throws Exception {
        UUID productId = registerInStock("");

        JsonNode product = data(adminDetail(productId)).get("product");
        assertThat(product.get("discountRate").asInt()).isZero();
        assertThat(prices(product)).containsExactly(Map.entry("256GB", "2278190/2278190"), Map.entry("512GB", "2478190/2478190"));
        assertThat(jdbcTemplate.queryForObject("SELECT discount_rate FROM products WHERE id = ?", Integer.class,
                (Object) UuidBinary.toBytes(productId))).isZero();
    }

    @Test
    @DisplayName("할인율은 0~99 의 정수 — 등록 · 수정 모두 밖이면 400(field=discountRate)")
    void rateOutOfRangeIs400() throws Exception {
        for (String rate : new String[] {"-1", "100", "5.5"}) {
            expectValidation(mockMvc.perform(admin(post(PATH)).header("Idempotency-Key", "k-" + ShopFixtures.unique())
                    .content(inStockBody("\"discountRate\": " + rate + ","))), "discountRate");
        }
        UUID productId = registerInStock("");
        for (String rate : new String[] {"-1", "100"}) {
            expectValidation(edit(productId, "{ \"discountRate\": " + rate + " }"), "discountRate");
        }
        assertThat(data(adminDetail(productId)).get("product").get("discountRate").asInt()).isZero();
    }

    @Test
    @DisplayName("할인가가 100원보다 작아지는 옵션은 만들지 않는다 — 등록 · 할인율 · 기본가 · 조합 추가 모두 원인 칸의 400 이고 값은 그대로")
    void salePriceMustBeAtLeastHundredWon() throws Exception {
        // 정가 150 × 50% = 75
        expectValidation(mockMvc.perform(admin(post(PATH)).header("Idempotency-Key", "k-" + ShopFixtures.unique())
                .content(lowPriceBody("\"discountRate\": 50,", ""))), "combinations[storage=256GB]");
        // 0% 여도 정가 자체가 하한 밑이면 400
        expectValidation(mockMvc.perform(admin(post(PATH)).header("Idempotency-Key", "k-" + ShopFixtures.unique())
                .content(lowPriceBody("", "").replace("\"basePrice\": 150", "\"basePrice\": 99"))), "combinations[storage=256GB]");

        UUID productId = register(lowPriceBody("", ""));
        expectValidation(edit(productId, "{ \"discountRate\": 50 }"), "discountRate");
        expectValidation(edit(productId, "{ \"basePrice\": 99 }"), "basePrice");
        JsonNode unchanged = data(adminDetail(productId)).get("product");
        assertThat(unchanged.get("discountRate").asInt()).isZero();
        assertThat(prices(unchanged)).containsExactly(Map.entry("256GB", "150/150"), Map.entry("512GB", "200150/200150"));

        // 기본가와 할인율을 함께 보내면 새 할인율로 판정한다 — 1000 × 50% = 500
        edit(productId, "{ \"basePrice\": 1000, \"discountRate\": 50 }").andExpect(status().isOk());
        // 그 뒤 할인율을 내리며 기본가를 150 으로 — 옛 할인율(50%)로 판정하면 75 라 거절된다
        JsonNode lowered = data(edit(productId, "{ \"basePrice\": 150, \"discountRate\": 0 }").andExpect(status().isOk())).get("product");
        assertThat(prices(lowered)).containsExactly(Map.entry("256GB", "150/150"), Map.entry("512GB", "200150/200150"));

        // 256GB 를 빼고 50% 로 등록 → 그 조합을 더하면 75
        UUID withoutBase = register(lowPriceBody("\"discountRate\": 50,", ", \"excluded\": true"));
        expectValidation(mockMvc.perform(admin(post(PATH + "/{id}/variants", withoutBase))
                .content("{ \"selections\": { \"storage\": \"256GB\" } }")), "selections");
        assertThat(data(adminDetail(withoutBase)).get("product").get("variants")).hasSize(1);
    }

    @Test
    @DisplayName("일반 상품은 언제든 할인율만 따로 고친다 — 고친 값이 바로 가격에 걸리고 0 으로 되돌리면 정가다")
    void inStockRateIsEditableAnyTime() throws Exception {
        UUID productId = registerInStock("");

        JsonNode edited = data(edit(productId, "{ \"discountRate\": 10 }").andExpect(status().isOk())).get("product");
        assertThat(edited.get("discountRate").asInt()).isEqualTo(10);
        // 2,278,190 × 0.9 = 2,050,371 · 2,478,190 × 0.9 = 2,230,371
        assertThat(prices(edited)).containsExactly(Map.entry("256GB", "2050300/2278190"), Map.entry("512GB", "2230300/2478190"));

        // 같은 트랜잭션의 응답이 아니라 다시 읽은 값 — 저장되지 않는 칸이면 여기서 0 이 나온다
        assertThat(prices(data(adminDetail(productId)).get("product")))
                .containsExactly(Map.entry("256GB", "2050300/2278190"), Map.entry("512GB", "2230300/2478190"));

        JsonNode reverted = data(edit(productId, "{ \"discountRate\": 0 }").andExpect(status().isOk())).get("product");
        assertThat(prices(reverted)).containsExactly(Map.entry("256GB", "2278190/2278190"), Map.entry("512GB", "2478190/2478190"));
    }

    @Test
    @DisplayName("사전예약은 오픈 3분 전까지만 할인율을 고친다 — 고치면 preorder 에 상품 변경을 알리고, 오픈 2분 전 · 오픈 뒤는 409 이고 할인율은 그대로")
    void preorderRateIsFrozenBeforeOpen() throws Exception {
        Instant now = Instant.now();
        UUID beforeOpen = registerPreorder("\"discountRate\": 5,", now.plus(HOUR.multipliedBy(2)));
        fixtures.moveCampaignOpensAt(beforeOpen, now.plus(Duration.ofMinutes(5)));
        long changedBefore = productChangedEvents(beforeOpen);
        edit(beforeOpen, "{ \"discountRate\": 20 }").andExpect(status().isOk());
        assertThat(data(adminDetail(beforeOpen)).get("product").get("discountRate").asInt()).isEqualTo(20);
        // preorder 의 접수용 상품 사본(가격 포함)을 비워야 새 할인가로 접수한다
        assertThat(productChangedEvents(beforeOpen)).isEqualTo(changedBefore + 1);

        for (Instant opensAt : new Instant[] {now.plus(Duration.ofMinutes(2)), now.minus(HOUR)}) {
            UUID frozen = registerPreorder("\"discountRate\": 5,", now.plus(HOUR.multipliedBy(2)));
            fixtures.moveCampaignOpensAt(frozen, opensAt);
            edit(frozen, "{ \"discountRate\": 20 }").andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("STATE_CONFLICT"));
            assertThat(data(adminDetail(frozen)).get("product").get("discountRate").asInt()).isEqualTo(5);
        }
    }

    @Test
    @DisplayName("목록의 minPrice 는 최저 할인가, minListPrice 는 그 정가 — 가격순도 할인가로 줄 세운다")
    void listShowsDiscountedMinimumAndSortsByIt() throws Exception {
        String tag = "dc" + ShopFixtures.unique();
        UUID half = fixtures.product(categoryId, "IN_STOCK", "ACTIVE", "반값", tag);
        fixtures.option(half, "ACTIVE", new java.math.BigDecimal("1000000"));
        fixtures.option(half, "ACTIVE", new java.math.BigDecimal("1200000"));
        fixtures.option(half, "PAUSED", new java.math.BigDecimal("100000"));   // 판매 중지는 최저가에서 빠진다
        jdbcTemplate.update("UPDATE products SET discount_rate = 50 WHERE id = ?", (Object) UuidBinary.toBytes(half));
        UUID full = fixtures.product(categoryId, "IN_STOCK", "ACTIVE", "정가", tag);
        fixtures.option(full, "ACTIVE", new java.math.BigDecimal("600000"));
        fixtures.registration(half);
        fixtures.registration(full);
        fixtures.stockReady(half);
        fixtures.stockReady(full);

        JsonNode items = data(mockMvc.perform(get("/api/v1/products").param("q", tag).param("sort", "PRICE_ASC"))
                .andExpect(status().isOk())).get("items");
        assertThat(items).hasSize(2);
        // 정가순이면 600,000(정가) 이 먼저다
        assertThat(items.get(0).get("productId").asString()).isEqualTo(half.toString());
        assertThat(items.get(0).get("minPrice").decimalValue()).isEqualByComparingTo("500000");
        assertThat(items.get(0).get("minListPrice").decimalValue()).isEqualByComparingTo("1000000");
        assertThat(items.get(0).get("discountRate").asInt()).isEqualTo(50);
        assertThat(items.get(1).get("minPrice").decimalValue()).isEqualByComparingTo("600000");
        assertThat(items.get(1).get("minListPrice").decimalValue()).isEqualByComparingTo("600000");
        assertThat(items.get(1).get("discountRate").asInt()).isZero();

        JsonNode descending = data(mockMvc.perform(get("/api/v1/products").param("q", tag).param("sort", "PRICE_DESC"))).get("items");
        assertThat(descending.get(0).get("productId").asString()).isEqualTo(full.toString());

        JsonNode admin = data(mockMvc.perform(get(PATH).param("q", tag).with(user("admin").roles("ADMIN"))).andExpect(status().isOk()));
        for (JsonNode item : admin.get("items")) {
            if (item.get("productId").asString().equals(half.toString())) {
                assertThat(item.get("minPrice").decimalValue()).isEqualByComparingTo("500000");
                assertThat(item.get("minListPrice").decimalValue()).isEqualByComparingTo("1000000");
                assertThat(item.get("discountRate").asInt()).isEqualTo(50);
            }
        }
    }

    @Test
    @DisplayName("내부 조회의 price 는 할인가 — 장바구니 · 주문(order)과 예약 접수(preorder)가 이 값을 복사한다")
    void internalLookupsCarryTheDiscountedPrice() throws Exception {
        UUID inStock = registerInStock("\"discountRate\": 5,");
        UUID option256 = optionIdOf(inStock, "256GB");
        mockMvc.perform(get("/internal/options").param("ids", option256.toString()).with(user("657").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].price").value(2164200))
                // 보증 추가금은 할인하지 않는다
                .andExpect(jsonPath("$.data.items[0].warranty.surcharge").value(150000));

        UUID preorder = registerPreorder("\"discountRate\": 7,", Instant.now().plus(HOUR.multipliedBy(2)));
        JsonNode options = data(mockMvc.perform(get("/internal/products/{id}/options", preorder).with(user("657").roles("USER")))
                .andExpect(status().isOk())).get("options");
        Map<String, String> byTitle = new LinkedHashMap<>();
        options.forEach(option -> byTitle.put(option.get("title").asString(), option.get("price").decimalValue().toPlainString()));
        // 2,278,190 × 0.93 = 2,118,716.7 · 2,478,190 × 0.93 = 2,304,716.7
        assertThat(byTitle).containsExactly(Map.entry("256GB", "2118700"), Map.entry("512GB", "2304700"));
    }

    // ── 도우미 ──────────────────────────────────────────────────────────────

    /** 용량 축 하나(256GB +0 · 512GB +200,000), 기본가 2,278,190(100원 미만이 있는 값), 보증 150,000. extra 는 본문 중간에 끼울 JSON 조각. */
    private String inStockBody(String extra) {
        return body("IN_STOCK", extra + """
                "combinations": [ { "selections": { "storage": "256GB" }, "stock": 3 }, { "selections": { "storage": "512GB" }, "stock": 3 } ],
                """);
    }

    /** 기본가 150, 256GB(+0) · 512GB(+200,000). excluded256 은 256GB 조합 설정 뒤에 붙일 JSON 조각. */
    private String lowPriceBody(String extra, String excluded256) {
        return inStockBody(extra).replace("\"basePrice\": 2278190", "\"basePrice\": 150")
                .replace("{ \"selections\": { \"storage\": \"256GB\" }, \"stock\": 3 }",
                        excluded256.isEmpty() ? "{ \"selections\": { \"storage\": \"256GB\" }, \"stock\": 3 }"
                                : "{ \"selections\": { \"storage\": \"256GB\" }" + excluded256 + " }");
    }

    private String body(String saleMode, String extra) {
        return """
                {
                  "categoryId": "%s", "saleMode": "%s", "title": "Nova 1", "brand": "APPLE", "modelName": "A0001", "visible": true,
                  "basePrice": 2278190,
                  "warranty": { "offered": true, "surcharge": 150000 },
                  "optionAxes": [ { "key": "storage", "label": "용량", "values": [ { "value": "256GB" }, { "value": "512GB", "surcharge": 200000 } ] } ],
                  %s
                  "images": { "gallery": [ { "items": [ { "url": "https://img/n.jpg" } ] } ] }
                }
                """.formatted(categoryId, saleMode, extra);
    }

    private UUID registerInStock(String extra) throws Exception {
        return register(inStockBody(extra));
    }

    private UUID registerPreorder(String extra, Instant opensAt) throws Exception {
        UUID productId = register(body("PREORDER", extra + """
                "campaign": { "opensAt": "%s", "closesAt": "%s" },
                "shipmentBatches": [ { "batchNumber": 1, "positionFrom": 1, "positionTo": null, "estimatedShipStart": "2026-11-01", "estimatedShipEnd": "2026-11-07" } ],
                """.formatted(opensAt, opensAt.plus(Duration.ofDays(3)))));
        fixtures.campaign(productId, opensAt, opensAt.plus(Duration.ofDays(3)));
        return productId;
    }

    private UUID register(String body) throws Exception {
        ResultActions created = mockMvc.perform(admin(post(PATH)).header("Idempotency-Key", "k-" + ShopFixtures.unique()).content(body))
                .andExpect(status().isCreated());
        return UUID.fromString(data(created).get("registration").get("productId").asString());
    }

    private ResultActions edit(UUID productId, String body) throws Exception {
        return mockMvc.perform(admin(patch(PATH + "/{id}", productId)).content(body));
    }

    private ResultActions adminDetail(UUID productId) throws Exception {
        return mockMvc.perform(get(PATH + "/{id}", productId).with(user("admin").roles("ADMIN"))).andExpect(status().isOk());
    }

    private long productChangedEvents(UUID productId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM catalog_outbox_events WHERE aggregate_id = ? AND event_type = 'PREORDER_PRODUCT_CHANGED'",
                Long.class, (Object) UuidBinary.toBytes(productId));
    }

    private UUID optionIdOf(UUID productId, String storage) throws Exception {
        for (JsonNode variant : data(adminDetail(productId)).get("product").get("variants")) {
            if (variant.get("selections").get("storage").asString().equals(storage)) {
                return UUID.fromString(variant.get("variantId").asString());
            }
        }
        throw new AssertionError("no variant " + storage);
    }

    /** 용량 → "할인가/정가". 옵션 순서(만든 순) 그대로. */
    private static Map<String, String> prices(JsonNode product) {
        Map<String, String> byStorage = new LinkedHashMap<>();
        List<JsonNode> variants = new ArrayList<>();
        product.get("variants").forEach(variants::add);
        for (JsonNode variant : variants) {
            byStorage.put(variant.get("selections").get("storage").asString(),
                    variant.get("price").decimalValue().toPlainString() + "/" + variant.get("listPrice").decimalValue().toPlainString());
        }
        return byStorage;
    }

    private static MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder builder) {
        return builder.contentType(MediaType.APPLICATION_JSON).with(user("admin").roles("ADMIN"));
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
