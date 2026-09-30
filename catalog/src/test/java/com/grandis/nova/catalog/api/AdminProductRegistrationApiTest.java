package com.grandis.nova.catalog.api;

import com.grandis.nova.catalog.support.CatalogIntegrationTest;
import com.grandis.nova.catalog.support.ShopFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@CatalogIntegrationTest
@AutoConfigureMockMvc
class AdminProductRegistrationApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PATH = "/api/v1/admin/products";

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;

    ShopFixtures fixtures;
    Long categoryId;
    Instant opensAt;

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
        categoryId = fixtures.category();
        opensAt = Instant.now().plus(Duration.ofHours(2));
    }

    // ── 등록 ① ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("등록 ①")
    class Register {

        @Test
        @DisplayName("사전예약 상품 — 조합 자동 생성 · 제외 · 가격 계산과 수동 지정 · 사진 묶음 · 보증 · 비공개 · 등록 기록")
        void registersPreorderProduct() throws Exception {
            String body = preorderBody("""
                    "combinations": [
                      { "selections": { "color": "화이트", "storage": "512 gb" }, "excluded": true },
                      { "selections": { "color": " 블랙 ", "storage": "256GB" }, "sku": "BLK-256", "price": 1250000 }
                    ],
                    "images": {
                      "gallery": [
                        { "color": "블랙", "items": [ { "url": "https://img/b0.jpg" }, { "url": "https://img/b1.jpg", "primary": true } ] },
                        { "color": "화이트", "items": [ { "url": "https://img/w0.jpg" } ] }
                      ],
                      "detail": [ { "section": " 제품 사양 ", "items": [ { "url": "https://img/spec.jpg" } ] } ]
                    },
                    """);

            JsonNode data = data(register("k-" + ShopFixtures.unique(), body).andExpect(status().isCreated()));
            Long productId = data.get("registration").get("productId").asLong();
            assertThat(data.get("registration").get("completed").asBoolean()).isFalse();
            assertThat(data.get("registration").get("blockedReason").isNull()).isTrue();

            JsonNode product = data.get("product");
            assertThat(product.get("visible").asBoolean()).as("등록 중에는 비공개").isFalse();
            assertThat(product.get("status").asString()).isEqualTo("ACTIVE");
            assertThat(product.get("basePrice").decimalValue()).isEqualByComparingTo("1000000");
            assertThat(product.get("warranty").get("surcharge").decimalValue()).isEqualByComparingTo("150000");
            assertThat(product.get("campaign").isNull()).as("회차는 ② 뒤에 생긴다").isTrue();
            // 축 2 × 값 2 = 4 조합 중 화이트/512 제외 → 3
            List<JsonNode> variants = list(product.get("variants"));
            assertThat(variants).hasSize(3);
            Map<String, JsonNode> bySku = new java.util.HashMap<>();
            variants.forEach(v -> bySku.put(v.get("sku").asString(), v));
            assertThat(bySku.keySet()).containsExactlyInAnyOrder("BLK-256", "블랙-512GB", "화이트-256GB");
            assertThat(bySku.get("BLK-256").get("price").decimalValue()).as("수동 지정").isEqualByComparingTo("1250000");
            assertThat(bySku.get("블랙-512GB").get("price").decimalValue()).as("기본가 + 512GB 추가금").isEqualByComparingTo("1400000");
            assertThat(bySku.get("화이트-256GB").get("price").decimalValue()).as("기본가 + 256GB 추가금").isEqualByComparingTo("1200000");
            assertThat(bySku.get("BLK-256").get("title").asString()).isEqualTo("블랙 / 256 GB");
            assertThat(bySku.get("BLK-256").get("selections").get("storage").asString()).isEqualTo("256GB");
            assertThat(bySku.get("BLK-256").get("filterAttributes").get("color").asString()).isEqualTo("블랙");
            assertThat(bySku.get("BLK-256").get("availableQuantity").isNull()).isTrue();
            assertThat(list(product.get("optionAxes")).stream().map(a -> a.get("key").asString()).toList())
                    .containsExactly("color", "storage");

            // 사진: 블랙 묶음은 두 번째가 대표, 화이트 묶음은 첫 장이 자동 대표, 상세 영역은 이름 정규화 · 대표 자동 지정 없음
            JsonNode gallery = product.get("images").get("gallery");
            assertThat(list(gallery).stream().map(b -> b.get("bundleKey").asString()).toList()).containsExactly("블랙", "화이트");
            assertThat(gallery.get(0).get("items").get(1).get("primary").asBoolean()).isTrue();
            assertThat(gallery.get(0).get("items").get(0).get("primary").asBoolean()).isFalse();
            assertThat(gallery.get(1).get("items").get(0).get("primary").asBoolean()).isTrue();
            JsonNode detail = product.get("images").get("detail").get(0);
            assertThat(detail.get("bundleKey").asString()).isEqualTo("제품 사양");
            assertThat(detail.get("items").get(0).get("primary").asBoolean()).isFalse();
            assertThat(product.get("imageUrl").asString()).as("기본 묶음이 없으니 사전순 첫 묶음(블랙)의 대표").isEqualTo("https://img/b1.jpg");

            // DB: 비공개 · 수동 가격 표식 · 조합 키 · 등록 기록
            assertThat(jdbcTemplate.queryForObject("SELECT visible FROM products WHERE id = ?", Boolean.class, productId)).isFalse();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT price_overridden FROM product_options WHERE product_id = ? AND sku = 'BLK-256'", Boolean.class, productId)).isTrue();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM product_options WHERE product_id = ? AND combination_key IS NOT NULL", Long.class, productId)).isEqualTo(3L);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM product_option_selections WHERE product_id = ?", Long.class, productId)).isEqualTo(6L);
            Map<String, Object> registration = jdbcTemplate.queryForMap(
                    "SELECT requested_visible, completed_at FROM product_registrations WHERE product_id = ?", productId);
            assertThat(registration.get("requested_visible")).isEqualTo(true);
            assertThat(registration.get("completed_at")).isNull();
        }

        @Test
        @DisplayName("일반 상품 — 축 없이 옵션 하나, 초기 재고는 ② 에 넘길 계획에만 있고 catalog 는 재고 표를 쓰지 않는다")
        void registersInStockProductWithoutAxes() throws Exception {
            String body = """
                    {
                      "categoryId": %d, "saleMode": "IN_STOCK", "title": "케이블", "visible": false, "basePrice": 9000,
                      "combinations": [ { "selections": {}, "stock": 7 } ]
                    }
                    """.formatted(categoryId);

            JsonNode data = data(register("k-" + ShopFixtures.unique(), body).andExpect(status().isCreated()));
            JsonNode product = data.get("product");
            assertThat(product.get("optionAxes")).isEmpty();
            JsonNode variant = product.get("variants").get(0);
            assertThat(variant.get("sku").asString()).isEqualTo("STD");
            assertThat(variant.get("title").asString()).isEqualTo("케이블");
            assertThat(variant.get("price").decimalValue()).isEqualByComparingTo("9000");
            assertThat(variant.get("availableQuantity").asInt()).as("order 가 재고를 넣기 전이라 0").isEqualTo(0);
            Long productId = data.get("registration").get("productId").asLong();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM option_inventories inv JOIN product_options o ON o.id = inv.option_id WHERE o.product_id = ?",
                    Long.class, productId)).as("catalog 는 option_inventories 를 쓰지 않는다").isZero();
            assertThat(jdbcTemplate.queryForObject("SELECT requested_visible FROM product_registrations WHERE product_id = ?",
                    Boolean.class, productId)).isFalse();
        }
    }

    // ── 멱등 ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Idempotency-Key")
    class Idempotency {

        @Test
        @DisplayName("같은 키는 본문 내용을 대조하지 않는다 — 제목 · 가격이 달라도 새 상품을 만들지 않고 첫 등록을 돌려준다")
        void sameKeyReplaysRegardlessOfBody() throws Exception {
            String key = "k-" + ShopFixtures.unique();
            String original = preorderBody("""
                    "combinations": [ { "selections": { "color": "블랙", "storage": "256GB" }, "sku": "BLK-256" } ],
                    """);
            Long first = productIdOf(register(key, original).andExpect(status().isCreated()));
            long before = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM products", Long.class);

            String different = original.replace("\"title\": \"Nova 1\"", "\"title\": \"Nova 2\"")
                    .replace("\"basePrice\": 1000000", "\"basePrice\": 2000000");
            assertThat(different).contains("Nova 2").contains("2000000");
            ResultActions replay = register(key, different);
            // 미완료 등록이라 재개 대상(202). 완료 뒤에는 200
            replay.andExpect(status().isAccepted());
            assertThat(productIdOf(replay)).isEqualTo(first);
            assertThat(data(replay).get("product").isNull()).as("200 · 202 는 고정 필드만").isTrue();
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM products", Long.class)).isEqualTo(before);
            // 두 번째 본문은 어디에도 반영되지 않는다 — 첫 등록의 제목 · 가격이 그대로다
            assertThat(jdbcTemplate.queryForMap("SELECT title, base_price FROM products WHERE id = ?", first))
                    .containsEntry("title", "Nova 1")
                    .hasEntrySatisfying("base_price", price -> assertThat(((Number) price).longValue()).isEqualTo(1000000L));

            jdbcTemplate.update("UPDATE product_registrations SET completed_at = UTC_TIMESTAMP(6) WHERE product_id = ?", first);
            ResultActions completed = register(key, original).andExpect(status().isOk());
            assertThat(productIdOf(completed)).isEqualTo(first);
            assertThat(data(completed).get("registration").get("completed").asBoolean()).isTrue();
            assertThat(data(completed).get("product").isNull()).isTrue();

            // 키는 앞뒤를 트림한다 — 칼럼(utf8mb4_bin, PAD SPACE)은 뒤 공백만 같게 보고 앞 공백은 다른 키로 본다(MySQL 8.4.11 실측).
            // 앱이 양쪽을 잘라야 "  k" 와 "k" 가 한 등록이다
            assertThat(productIdOf(register("  " + key + "  ", original).andExpect(status().isOk()))).isEqualTo(first);
        }

        @Test
        @DisplayName("응답을 잃고 오픈 30분 전이 지난 뒤 다시 보내도 같은 키면 202 와 같은 productId — 새 키로는 400")
        void lateResendStillReplays() throws Exception {
            String key = "k-" + ShopFixtures.unique();
            Long first = productIdOf(register(key, preorderBody("")).andExpect(status().isCreated()));

            // 오픈 시각이 이미 지난 본문 — 최초 등록이면 opensAt 검사에 걸린다
            String late = preorderBody("").replace(opensAt.toString(), Instant.now().minus(Duration.ofMinutes(1)).toString());
            assertThat(late).isNotEqualTo(preorderBody(""));
            ResultActions replay = register(key, late).andExpect(status().isAccepted());
            assertThat(productIdOf(replay)).isEqualTo(first);

            // 대조군: 같은 본문을 새 키로 보내면 검사에 걸린다
            expectValidation(register("k-" + ShopFixtures.unique(), late), "campaign.opensAt");
        }

        @Test
        @DisplayName("막힌 등록은 재개 대상이 아니라 409 REGISTRATION_BLOCKED")
        void blockedRegistrationIsConflict() throws Exception {
            String key = "k-" + ShopFixtures.unique();
            Long productId = productIdOf(register(key, preorderBody("")).andExpect(status().isCreated()));
            jdbcTemplate.update("UPDATE product_registrations SET blocked_reason = 'OPENED_BEFORE_COMPLETE' WHERE product_id = ?", productId);

            register(key, preorderBody(""))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("REGISTRATION_BLOCKED"))
                    .andExpect(jsonPath("$.error.details.blockedReason").value("OPENED_BEFORE_COMPLETE"));
        }

        @Test
        @DisplayName("헤더가 없으면 400 IDEMPOTENCY_KEY_REQUIRED")
        void keyHeaderRequired() throws Exception {
            mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(preorderBody(""))
                            .with(user("admin").roles("ADMIN")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        }

        @Test
        @DisplayName("같은 새 키가 동시에 오면 하나만 저장되고 나머지는 409 — 상품도 하나만 생긴다")
        void concurrentSameKeyCreatesOnce() throws Exception {
            String key = "k-" + ShopFixtures.unique();
            String body = preorderBody("");
            long before = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM products", Long.class);

            List<Integer> statuses = concurrently(4, () -> register(key, body).andReturn().getResponse().getStatus());

            // 겹친 요청은 409, 먼저 끝난 뒤 도착한 요청은 202(미완료 재전송). 201 은 정확히 하나
            assertThat(statuses).containsAnyOf(409, 202).containsOnlyOnce(201);
            assertThat(statuses).allSatisfy(s -> assertThat(s).isIn(201, 202, 409));
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM products", Long.class)).isEqualTo(before + 1);
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM product_registrations WHERE idempotency_key = ?",
                    Long.class, key)).isEqualTo(1L);
        }

        @Test
        @DisplayName("키로 상태를 묻는다 — 없으면 404")
        void statusByKey() throws Exception {
            String key = "k-" + ShopFixtures.unique();
            Long productId = productIdOf(register(key, preorderBody("")).andExpect(status().isCreated()));

            mockMvc.perform(get(PATH + "/registrations/{key}", key).with(user("admin").roles("ADMIN")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.productId").value(productId))
                    .andExpect(jsonPath("$.data.completed").value(false))
                    .andExpect(jsonPath("$.data.campaignSetAt").doesNotExist());
            mockMvc.perform(get(PATH + "/registrations/{key}", "nope-" + key).with(user("admin").roles("ADMIN")))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("REGISTRATION_NOT_FOUND"));
        }
    }

    // ── 검증 ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("검증")
    class Validation {

        @Test
        @DisplayName("사전예약 오픈은 지금 + 30분 뒤여야 하고, 일반은 회차를 받지 않는다")
        void saleModeShape() throws Exception {
            String soon = preorderBody("").replace(opensAt.toString(), Instant.now().plus(Duration.ofMinutes(29)).toString());
            expectValidation(register("k-" + ShopFixtures.unique(), soon), "campaign.opensAt");
            String inStockWithCampaign = preorderBody("").replace("\"saleMode\": \"PREORDER\"", "\"saleMode\": \"IN_STOCK\"");
            expectValidation(register("k-" + ShopFixtures.unique(), inStockWithCampaign), "campaign");
            String noBatches = preorderBody("").replace("\"shipmentBatches\": [ { \"batchNumber\": 1, \"positionFrom\": 1, \"positionTo\": null, \"estimatedShipStart\": \"2026-11-01\", \"estimatedShipEnd\": \"2026-11-07\" } ]", "\"shipmentBatches\": []");
            expectValidation(register("k-" + ShopFixtures.unique(), noBatches), "shipmentBatches");
        }

        @Test
        @DisplayName("용량 형식 · 축에 없는 값 · 같은 SKU · 묶음 11장 · 소수 금액은 400")
        void optionAndImageRules() throws Exception {
            expectValidation(register("k-" + ShopFixtures.unique(), preorderBody("").replace("\"value\": \"512 GB\"", "\"value\": \"512\"")),
                    "optionAxes[1].values[1].value");
            expectValidation(register("k-" + ShopFixtures.unique(), preorderBody("""
                    "combinations": [ { "selections": { "color": "레드", "storage": "256GB" } } ],
                    """)), "combinations[0].selections");
            expectValidation(register("k-" + ShopFixtures.unique(), preorderBody("""
                    "combinations": [ { "selections": { "color": "블랙", "storage": "256GB" }, "sku": "X" },
                                      { "selections": { "color": "화이트", "storage": "256GB" }, "sku": "X" } ],
                    """)), "combinations[color=화이트, storage=256GB].sku");
            StringBuilder eleven = new StringBuilder();
            for (int i = 0; i < 11; i++) {
                eleven.append(i > 0 ? "," : "").append("{ \"url\": \"https://img/%d.jpg\" }".formatted(i));
            }
            expectValidation(register("k-" + ShopFixtures.unique(), preorderBody(
                    "\"images\": { \"gallery\": [ { \"color\": \"블랙\", \"items\": [" + eleven + "] } ] },")), "images.gallery[0].items");
            // 상세 영역에는 상한이 없다
            register("k-" + ShopFixtures.unique(), preorderBody(
                    "\"images\": { \"detail\": [ { \"section\": \"유의사항\", \"items\": [" + eleven + "] } ] },"))
                    .andExpect(status().isCreated());
            expectValidation(register("k-" + ShopFixtures.unique(), preorderBody("").replace("\"basePrice\": 1000000", "\"basePrice\": 1000000.5")),
                    "basePrice");
            expectValidation(register("k-" + ShopFixtures.unique(), preorderBody("").replace("\"title\": \"Nova 1\"", "\"title\": \"\"")),
                    "title");
            // 형식이 틀린 값은 그 칸 이름으로 — 본문 전체(body)가 아니다
            expectValidation(register("k-" + ShopFixtures.unique(), preorderBody("").replace("\"basePrice\": 1000000", "\"basePrice\": \"abc\"")),
                    "basePrice");
            // 같은 키가 두 번이면 뒤의 것이 조용히 이기지 않는다
            expectValidation(register("k-" + ShopFixtures.unique(), preorderBody("").replace("\"title\": \"Nova 1\"", "\"title\": \"Nova 1\", \"title\": \"Nova 2\"")),
                    "body");
        }

        @Test
        @DisplayName("사전예약은 재고를 받지 않고(0 도), 일반은 제외하지 않은 조합마다 재고가 필수다")
        void stockRules() throws Exception {
            expectValidation(register("k-" + ShopFixtures.unique(), preorderBody("""
                    "combinations": [ { "selections": { "color": "블랙", "storage": "256GB" }, "stock": 0 } ],
                    """)), "combinations[color=블랙, storage=256GB].stock");
            String inStock = """
                    { "categoryId": %d, "saleMode": "IN_STOCK", "title": "케이스", "visible": true, "basePrice": 10000,
                      "optionAxes": [ { "key": "color", "label": "색상", "values": [ { "value": "블랙" }, { "value": "화이트" } ] } ],
                      "combinations": [ { "selections": { "color": "블랙" }, "stock": 5 } %s ] }
                    """;
            expectValidation(register("k-" + ShopFixtures.unique(), inStock.formatted(categoryId, "")),
                    "combinations[color=화이트].stock");
            expectValidation(register("k-" + ShopFixtures.unique(), inStock.formatted(categoryId,
                    ", { \"selections\": { \"color\": \"화이트\" }, \"stock\": -1 }")), "combinations[color=화이트].stock");
            register("k-" + ShopFixtures.unique(), inStock.formatted(categoryId,
                    ", { \"selections\": { \"color\": \"화이트\" }, \"excluded\": true }")).andExpect(status().isCreated());
        }

        @Test
        @DisplayName("사진 묶음 — color 축이 있으면 색상별만(공통 묶음 400), 없으면 기본 묶음만, 없는 색상 400, 대표 둘 400, 같은 묶음 400")
        void bundleRules() throws Exception {
            expectValidation(register("k-" + ShopFixtures.unique(), preorderBody("""
                    "images": { "gallery": [ { "color": null, "items": [ { "url": "https://img/d.jpg" } ] } ] },
                    """)), "images.gallery[0].color");
            expectValidation(register("k-" + ShopFixtures.unique(), preorderBody("""
                    "images": { "gallery": [ { "color": "레드", "items": [ { "url": "https://img/r.jpg" } ] } ] },
                    """)), "images.gallery[0].color");
            expectValidation(register("k-" + ShopFixtures.unique(), preorderBody("""
                    "images": { "gallery": [ { "color": "블랙", "items": [ { "url": "https://img/1.jpg", "primary": true }, { "url": "https://img/2.jpg", "primary": true } ] } ] },
                    """)), "images.gallery[0].items");
            expectValidation(register("k-" + ShopFixtures.unique(), preorderBody("""
                    "images": { "gallery": [ { "color": "블랙", "items": [ { "url": "https://img/1.jpg" } ] }, { "color": " 블랙 ", "items": [ { "url": "https://img/2.jpg" } ] } ] },
                    """)), "images.gallery[1].color");
            expectValidation(register("k-" + ShopFixtures.unique(), preorderBody("""
                    "images": { "detail": [ { "section": "Spec", "items": [ { "url": "https://img/1.jpg" } ] }, { "section": "spec", "items": [ { "url": "https://img/2.jpg" } ] } ] },
                    """)), "images.detail[1].section");
            String noColorAxis = """
                    { "categoryId": %d, "saleMode": "IN_STOCK", "title": "케이블", "visible": true, "basePrice": 9000,
                      "combinations": [ { "selections": {}, "stock": 1 } ],
                      "images": { "gallery": [ { "color": "블랙", "items": [ { "url": "https://img/1.jpg" } ] } ] } }
                    """.formatted(categoryId);
            expectValidation(register("k-" + ShopFixtures.unique(), noColorAxis), "images.gallery[0].color");
        }

        @Test
        @DisplayName("모르는 칸은 400 — 오타(exclude · stockQuantity)가 조용히 판매로 이어지지 않게")
        void unknownFieldsRejected() throws Exception {
            expectValidation(register("k-" + ShopFixtures.unique(), preorderBody("""
                    "combinations": [ { "selections": { "color": "화이트", "storage": "512GB" }, "exclude": true } ],
                    """)), "combinations[0].exclude");
            expectValidation(register("k-" + ShopFixtures.unique(), preorderBody("").replace("\"tags\": \"nova\"", "\"tag\": \"nova\"")),
                    "tag");
        }

        @Test
        @DisplayName("콜레이션이 같다고 보는 값(악센트 · 전각)은 같은 축에 둘 수 없고, 없는 카테고리 · 긴 표시명 · null 원소는 400")
        void collationAndReferenceRules() throws Exception {
            expectValidation(register("k-" + ShopFixtures.unique(), preorderBody("").replace("{ \"value\": \"화이트\" }", "{ \"value\": \"Rose\" }, { \"value\": \"Rosé\" }")),
                    "optionAxes[0].values[2].value");
            expectValidation(register("k-" + ShopFixtures.unique(), preorderBody("").replace("{ \"value\": \"화이트\" }", "{ \"value\": \"BLACK\" }, { \"value\": \"ＢＬＡＣＫ\" }")),
                    "optionAxes[0].values[2].value");
            // 확장 문자(ß=ss)는 앱의 흉내가 못 잡고 DB UNIQUE 가 잡는다 — 그래도 500 이 아니라 400 이어야 하고,
            // 그 전에 INSERT 된 상품 · 축은 남지 않아야 한다(실패 응답에 반쪽 상품 없음)
            int productsBefore = productsInCategory();
            expectValidation(register("k-" + ShopFixtures.unique(), preorderBody("").replace("{ \"value\": \"화이트\" }", "{ \"value\": \"Straße\" }, { \"value\": \"Strasse\" }")),
                    "optionAxes[0].values");
            expectValidation(register("k-" + ShopFixtures.unique(), preorderBody("""
                    "images": { "detail": [ { "section": "Straße", "items": [ { "url": "https://img/1.jpg" } ] }, { "section": "Strasse", "items": [ { "url": "https://img/2.jpg" } ] } ] },
                    """)), "images.detail[1].section");
            assertThat(productsInCategory()).as("400 뒤에 상품 행이 남지 않는다").isEqualTo(productsBefore);
            expectValidation(register("k-" + ShopFixtures.unique(), preorderBody("").replace("\"categoryId\": " + categoryId, "\"categoryId\": 999999999")),
                    "categoryId");
            // 표시명(값을 " / " 로 이은 것) 122자 — SKU 는 직접 줘서 SKU 길이 규칙에 먼저 안 걸리게
            String longColor = "화".repeat(60);
            String longStorage = "9".repeat(57) + "GB";
            expectValidation(register("k-" + ShopFixtures.unique(), preorderBody(
                    "\"combinations\": [ { \"selections\": { \"color\": \"" + longColor + "\", \"storage\": \"" + longStorage + "\" }, \"sku\": \"LONG\" } ],")
                    .replace("{ \"value\": \"화이트\" }", "{ \"value\": \"" + longColor + "\" }")
                    .replace("{ \"value\": \"512 GB\", \"surcharge\": 400000 }", "{ \"value\": \"" + longStorage + "\", \"surcharge\": 400000 }")),
                    "combinations[color=" + longColor + ", storage=" + longStorage + "]");
            expectValidation(register("k-" + ShopFixtures.unique(), preorderBody("").replace("{ \"value\": \"화이트\" }", "null")),
                    "optionAxes[0].values[1]");
            // 최상위 목록의 null 원소도 칸 경로로 400 (List.copyOf 의 NPE 로 "body" 가 되면 안 된다)
            expectValidation(register("k-" + ShopFixtures.unique(), preorderBody("\"combinations\": [ null ],")), "combinations[0]");
            expectValidation(register("k-" + ShopFixtures.unique(), preorderBody("\"images\": { \"gallery\": [ null ] },")), "images.gallery[0]");
            expectValidation(register("k-" + ShopFixtures.unique(), preorderBody("").replace(
                    "\"shipmentBatches\": [ { \"batchNumber\": 1", "\"shipmentBatches\": [ null, { \"batchNumber\": 1")), "shipmentBatches[0]");
        }

        @Test
        @DisplayName("배송 차수의 모양은 ① 전에 거른다 — 번호 · 시작 순번 양의 정수와 유일, 종료 ≥ 시작 또는 마지막만 null, 배송 종료 ≥ 시작")
        void shipmentBatchRules() throws Exception {
            String two = """
                    "shipmentBatches": [ { "batchNumber": %s, "positionFrom": %s, "positionTo": %s, "estimatedShipStart": "2026-11-01", "estimatedShipEnd": "%s" },
                                         { "batchNumber": %s, "positionFrom": %s, "positionTo": null, "estimatedShipStart": "2026-11-08", "estimatedShipEnd": "2026-11-14" } ]
                    """;
            String ok = two.formatted(1, 1, 100, "2026-11-07", 2, 101, 200);
            register("k-" + ShopFixtures.unique(), withBatches(ok)).andExpect(status().isCreated());
            expectValidation(register("k-" + ShopFixtures.unique(), withBatches(two.formatted(0, 1, 100, "2026-11-07", 2, 101, 200))), "shipmentBatches[0].batchNumber");
            expectValidation(register("k-" + ShopFixtures.unique(), withBatches(two.formatted(1, 1, 100, "2026-11-07", 1, 101, 200))), "shipmentBatches[1].batchNumber");
            expectValidation(register("k-" + ShopFixtures.unique(), withBatches(two.formatted(1, 0, 100, "2026-11-07", 2, 101, 200))), "shipmentBatches[0].positionFrom");
            expectValidation(register("k-" + ShopFixtures.unique(), withBatches(two.formatted(1, 1, 100, "2026-11-07", 2, 1, 200))), "shipmentBatches[1].positionFrom");
            expectValidation(register("k-" + ShopFixtures.unique(), withBatches(two.formatted(1, 1, "null", "2026-11-07", 2, 101, 200))), "shipmentBatches[0].positionTo");
            expectValidation(register("k-" + ShopFixtures.unique(), withBatches(two.formatted(1, 50, 49, "2026-11-07", 2, 101, 200))), "shipmentBatches[0].positionTo");
            expectValidation(register("k-" + ShopFixtures.unique(), withBatches(two.formatted(1, 1, 100, "2026-10-31", 2, 101, 200))), "shipmentBatches[0].estimatedShipEnd");
        }

        private String withBatches(String batches) {
            int from = preorderBody("").indexOf("\"shipmentBatches\"");
            return preorderBody("").substring(0, from) + batches + "}";
        }

        @Test
        @DisplayName("선택의 축 키도 소문자로 접고, 축을 안 덮거나 모르는 축이 섞이면 400, 같은 조합 둘 · 전부 제외 · 보증 미제공 추가금 · 마감 ≤ 오픈 · 축 키 중복 · 값 중복 · 소수 추가금 · 긴 SKU · 긴 키는 400")
        void remainingRules() throws Exception {
            register("k-" + ShopFixtures.unique(), preorderBody("""
                    "combinations": [ { "selections": { "Color": "블랙", "STORAGE": "256GB" }, "sku": "FOLDED" } ],
                    """)).andExpect(status().isCreated());
            expectValidation(register("k-" + ShopFixtures.unique(), preorderBody("""
                    "combinations": [ { "selections": { "color": "블랙", "storage": "256GB", "size": "L" } } ],
                    """)), "combinations[0].selections");
            expectValidation(register("k-" + ShopFixtures.unique(), preorderBody("""
                    "combinations": [ { "selections": { "color": "블랙" } } ],
                    """)), "combinations[0].selections");
            expectValidation(register("k-" + ShopFixtures.unique(), preorderBody("""
                    "combinations": [ { "selections": { "color": "블랙", "storage": "256GB" } }, { "selections": { "storage": "256 gb", "color": " 블랙" } } ],
                    """)), "combinations[1].selections");
            expectValidation(register("k-" + ShopFixtures.unique(), preorderBody("""
                    "combinations": [ { "selections": { "color": "블랙", "storage": "256GB" }, "excluded": true }, { "selections": { "color": "블랙", "storage": "512GB" }, "excluded": true },
                                      { "selections": { "color": "화이트", "storage": "256GB" }, "excluded": true }, { "selections": { "color": "화이트", "storage": "512GB" }, "excluded": true } ],
                    """)), "combinations");
            expectValidation(register("k-" + ShopFixtures.unique(), preorderBody("").replace("\"offered\": true", "\"offered\": false")), "warranty.surcharge");
            expectValidation(register("k-" + ShopFixtures.unique(), preorderBody("").replace(opensAt.plus(Duration.ofDays(3)).toString(), opensAt.toString())), "campaign.closesAt");
            expectValidation(register("k-" + ShopFixtures.unique(), preorderBody("").replace("\"key\": \"storage\"", "\"key\": \"COLOR\"")), "optionAxes[1].key");
            expectValidation(register("k-" + ShopFixtures.unique(), preorderBody("").replace("{ \"value\": \"화이트\" }", "{ \"value\": \" 블랙 \" }")), "optionAxes[0].values[1].value");
            expectValidation(register("k-" + ShopFixtures.unique(), preorderBody("").replace("\"surcharge\": 200000", "\"surcharge\": 200000.5")), "optionAxes[1].values[0].surcharge");
            expectValidation(register("k-" + ShopFixtures.unique(), preorderBody(
                    "\"combinations\": [ { \"selections\": { \"color\": \"블랙\", \"storage\": \"256GB\" }, \"sku\": \"" + "S".repeat(81) + "\" } ],")),
                    "combinations[0].sku");
            expectValidation(register("K".repeat(101), preorderBody("")), "Idempotency-Key");
            String inStockWithBatches = preorderBody("").replace("\"saleMode\": \"PREORDER\"", "\"saleMode\": \"IN_STOCK\"")
                    .replace("\"campaign\": { \"opensAt\": \"" + opensAt + "\", \"closesAt\": \"" + opensAt.plus(Duration.ofDays(3)) + "\" },", "");
            expectValidation(register("k-" + ShopFixtures.unique(), inStockWithBatches), "shipmentBatches");
        }
    }

    @Test
    @DisplayName("ADMIN 만 — 회원은 403, 익명은 401")
    void adminOnly() throws Exception {
        mockMvc.perform(post(PATH).header("Idempotency-Key", "k").contentType(MediaType.APPLICATION_JSON)
                        .content(preorderBody("")).with(user("657").roles("USER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(PATH).header("Idempotency-Key", "k").contentType(MediaType.APPLICATION_JSON)
                        .content(preorderBody("")))
                .andExpect(status().isUnauthorized());
    }

    // ── 도우미 ──────────────────────────────────────────────────────────────

    /** 색상(블랙 · 화이트) × 용량(256 GB · 512 GB, 추가금 200000 · 400000). extra 는 본문 중간에 끼울 JSON 조각. */
    private String preorderBody(String extra) {
        return """
                {
                  "categoryId": %d,
                  "saleMode": "PREORDER",
                  "title": "Nova 1", "description": "설명", "tags": "nova",
                  "visible": true,
                  "basePrice": 1000000,
                  "warranty": { "offered": true, "surcharge": 150000 },
                  "optionAxes": [
                    { "key": "Color", "label": "색상", "values": [ { "value": "블랙" }, { "value": "화이트" } ] },
                    { "key": "storage", "label": "용량", "values": [ { "value": "256 GB", "surcharge": 200000 }, { "value": "512 GB", "surcharge": 400000 } ] }
                  ],
                  %s
                  "campaign": { "opensAt": "%s", "closesAt": "%s" },
                  "shipmentBatches": [ { "batchNumber": 1, "positionFrom": 1, "positionTo": null, "estimatedShipStart": "2026-11-01", "estimatedShipEnd": "2026-11-07" } ]
                }
                """.formatted(categoryId, extra, opensAt, opensAt.plus(Duration.ofDays(3)));
    }

    private ResultActions register(String key, String body) throws Exception {
        MockHttpServletRequestBuilder request = post(PATH).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body).with(user("admin").roles("ADMIN"));
        return mockMvc.perform(request);
    }

    private int productsInCategory() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM products WHERE category_id = ?", Integer.class, categoryId);
    }

    private static void expectValidation(ResultActions actions, String field) throws Exception {
        actions.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details.violations[0].field").value(field));
    }

    private static JsonNode data(ResultActions actions) throws Exception {
        return JSON.readTree(actions.andReturn().getResponse().getContentAsString()).get("data");
    }

    private static Long productIdOf(ResultActions actions) throws Exception {
        return data(actions).get("registration").get("productId").asLong();
    }

    private static List<JsonNode> list(JsonNode array) {
        List<JsonNode> out = new ArrayList<>();
        array.forEach(out::add);
        return out;
    }

    /** 작업을 전부 만든 뒤 한 신호로 동시에 출발시킨다. */
    private static <T> List<T> concurrently(int count, Callable<T> task) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(count);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }
}
