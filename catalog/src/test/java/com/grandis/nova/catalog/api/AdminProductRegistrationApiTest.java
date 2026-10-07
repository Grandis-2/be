package com.grandis.nova.catalog.api;

import com.grandis.nova.catalog.option.OptionCombination;
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
        @DisplayName("사전예약 상품 — 조합 자동 생성 · 제외 · 가격 계산(기본가 + 추가금) · 사진 묶음 · 보증 · 고른 공개 여부 · 등록 기록 · 회차 이벤트")
        void registersPreorderProduct() throws Exception {
            String body = preorderBody("""
                    "combinations": [
                      { "selections": { "color": "화이트", "storage": "512 gb" }, "excluded": true },
                      { "selections": { "color": " 블랙 ", "storage": "256GB" }, "sku": "BLK-256" }
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
            assertThat(data.get("registration").get("completed").asBoolean()).as("회차 이벤트는 커밋 뒤에 나간다 — 아직 준비 전").isFalse();
            assertThat(data.get("registration").has("blockedReason")).as("막힘 · 단계 칸은 없다").isFalse();

            JsonNode product = data.get("product");
            assertThat(product.get("visible").asBoolean()).as("관리자가 고른 공개 여부가 바로 담긴다 — 노출은 준비가 정한다").isTrue();
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
            assertThat(bySku.get("BLK-256").get("price").decimalValue()).as("SKU 만 지정해도 가격은 기본가 + 256GB 추가금").isEqualByComparingTo("1200000");
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
            assertThat(product.get("imageUrl").asString()).as("첫 색상(블랙)의 첫 장 — 대표 표시(b1)가 아니다").isEqualTo("https://img/b0.jpg");

            // DB: 고른 공개 여부 · 조합 키 · 등록 기록 · 회차 이벤트
            assertThat(jdbcTemplate.queryForObject("SELECT visible FROM products WHERE id = ?", Boolean.class, productId)).isTrue();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM product_options WHERE product_id = ? AND combination_key IS NOT NULL", Long.class, productId)).isEqualTo(3L);
            assertThat(jdbcTemplate.queryForList(
                    "SELECT combination_key FROM product_options WHERE product_id = ?", String.class, productId))
                    .as("조합마다 축 둘의 값 id").allSatisfy(key -> assertThat(OptionCombination.valueIdsOf(key)).hasSize(2));
            assertThat(jdbcTemplate.queryForObject("SELECT idempotency_key IS NOT NULL FROM products WHERE id = ?",
                    Boolean.class, productId)).isTrue();
            assertThat(jdbcTemplate.queryForList("SELECT event_type FROM catalog_outbox_events WHERE aggregate_id = ?",
                    String.class, productId)).containsExactly("PREORDER_PRODUCT_REGISTERED");
        }

        @Test
        @DisplayName("일반 상품 — 축 없이 옵션 하나, 초기 재고는 order 로 가는 이벤트에만 있고 catalog 는 재고 표를 쓰지 않는다")
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
            assertThat(jdbcTemplate.queryForObject("SELECT visible FROM products WHERE id = ?", Boolean.class, productId)).isFalse();
            Map<String, Object> event = jdbcTemplate.queryForMap(
                    "SELECT event_type, JSON_EXTRACT(payload, '$.items[0].stockTotal') AS stock, "
                            + "JSON_EXTRACT(payload, '$.items[0].optionId') AS option_id FROM catalog_outbox_events WHERE aggregate_id = ?",
                    productId);
            assertThat(event.get("event_type")).isEqualTo("IN_STOCK_PRODUCT_REGISTERED");
            assertThat(event.get("stock")).isEqualTo("7");
            assertThat(event.get("option_id")).isEqualTo(String.valueOf(variant.get("variantId").asLong()));
        }

        @Test
        @DisplayName("값 추가금은 받은 표기와 상관없이 정수로 문서에 담긴다 — 1.5e3 은 응답 · 저장 모두 1500(JSON 실수 1500.0 이 아니다)")
        void optionValueSurchargeIsStoredAsPlainInteger() throws Exception {
            JsonNode data = data(register("k-" + ShopFixtures.unique(), preorderBody("").replace("\"surcharge\": 200000", "\"surcharge\": 1.5e3"))
                    .andExpect(status().isCreated()));
            Long productId = data.get("registration").get("productId").asLong();

            assertThat(data.get("product").get("optionAxes").get(1).get("values").get(0).get("surcharge").toString()).isEqualTo("1500");
            assertThat(jdbcTemplate.queryForObject("SELECT JSON_TYPE(JSON_EXTRACT(options, '$.axes[1].values[0].surcharge')) FROM products WHERE id = ?",
                    String.class, productId)).isEqualTo("INTEGER");
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
            // 준비 전(회차 행 없음)이라 202. 준비 뒤에는 200
            replay.andExpect(status().isAccepted());
            assertThat(productIdOf(replay)).isEqualTo(first);
            assertThat(data(replay).get("product").isNull()).as("200 · 202 는 고정 필드만").isTrue();
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM products", Long.class)).isEqualTo(before);
            // 두 번째 본문은 어디에도 반영되지 않는다 — 첫 등록의 제목 · 가격이 그대로다
            assertThat(jdbcTemplate.queryForMap("SELECT title, base_price FROM products WHERE id = ?", first))
                    .containsEntry("title", "Nova 1")
                    .hasEntrySatisfying("base_price", price -> assertThat(((Number) price).longValue()).isEqualTo(1000000L));

            // preorder 가 등록 이벤트를 처리해 회차 행을 만들면 준비가 끝난다 — 그 뒤 재전송은 200
            fixtures.campaign(first, Instant.now().plus(Duration.ofHours(2)), Instant.now().plus(Duration.ofDays(3)));
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
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM products WHERE idempotency_key = ?",
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
        @DisplayName("배송 차수의 모양은 저장 전에 거른다 — 번호 0 · 번호 중복 · 시작 0 · 시작 중복 · 상한 없는 차수 뒤의 차수 · 종료 < 시작 · 배송 종료 < 시작은 400")
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

        /**
         * preorder 가 등록 이벤트를 받아 차수를 만들 때 거는 규칙(ShipmentBatchPlan)과 같아야 한다 — 여기서 통과한 차수를 preorder 가 거절하면
         * 상품은 저장되고 이벤트는 DLQ 로 가 준비 전 상품만 남는다. 아래는 이전 등록 검증이 통과시키던 모양들이다.
         */
        @Test
        @DisplayName("배송 차수는 preorder 의 차수 규칙과 같다 — 번호는 순서대로 1 부터, 첫 차수는 순번 1 부터, 앞 차수 끝 + 1 로 이어짐(빈틈 · 겹침 400), 상한 없는 차수는 마지막 하나")
        void shipmentBatchesMatchPreorderPlan() throws Exception {
            String two = """
                    "shipmentBatches": [ { "batchNumber": %s, "positionFrom": %s, "positionTo": 100, "estimatedShipStart": "2026-11-01", "estimatedShipEnd": "2026-11-07" },
                                         { "batchNumber": %s, "positionFrom": %s, "positionTo": null, "estimatedShipStart": "2026-11-08", "estimatedShipEnd": "2026-11-14" } ]
                    """;
            register("k-" + ShopFixtures.unique(), withBatches("""
                    "shipmentBatches": [ { "batchNumber": 1, "positionFrom": 1, "positionTo": 100, "estimatedShipStart": "2026-11-01", "estimatedShipEnd": "2026-11-07" },
                                         { "batchNumber": 2, "positionFrom": 101, "positionTo": 250, "estimatedShipStart": "2026-11-08", "estimatedShipEnd": "2026-11-14" },
                                         { "batchNumber": 3, "positionFrom": 251, "positionTo": null, "estimatedShipStart": "2026-11-15", "estimatedShipEnd": "2026-11-21" } ]
                    """)).andExpect(status().isCreated());

            expectValidation(register("k-" + ShopFixtures.unique(), withBatches(two.formatted(1, 2, 2, 101))), "shipmentBatches[0].positionFrom");
            expectValidation(register("k-" + ShopFixtures.unique(), withBatches(two.formatted(1, 1, 2, 150))), "shipmentBatches[1].positionFrom");
            expectValidation(register("k-" + ShopFixtures.unique(), withBatches(two.formatted(1, 1, 2, 50))), "shipmentBatches[1].positionFrom");
            expectValidation(register("k-" + ShopFixtures.unique(), withBatches(two.formatted(2, 1, 1, 101))), "shipmentBatches[0].batchNumber");
            expectValidation(register("k-" + ShopFixtures.unique(), withBatches(two.formatted(1, 1, 3, 101))), "shipmentBatches[1].batchNumber");
            expectValidation(register("k-" + ShopFixtures.unique(), withBatches("""
                    "shipmentBatches": [ { "batchNumber": 1, "positionFrom": 1, "positionTo": 100, "estimatedShipStart": "2026-11-01", "estimatedShipEnd": "2026-11-07" } ]
                    """)), "shipmentBatches[0].positionTo");
            // 상한 없는 차수가 없으면 마지막 차수의 종료 순번을 가리킨다
            expectValidation(register("k-" + ShopFixtures.unique(), withBatches("""
                    "shipmentBatches": [ { "batchNumber": 1, "positionFrom": 1, "positionTo": 100, "estimatedShipStart": "2026-11-01", "estimatedShipEnd": "2026-11-07" },
                                         { "batchNumber": 2, "positionFrom": 101, "positionTo": 200, "estimatedShipStart": "2026-11-08", "estimatedShipEnd": "2026-11-14" } ]
                    """)), "shipmentBatches[1].positionTo");
            // 앞 차수가 순번 끝(Long 최댓값)이면 다음 차수가 이어질 수 없다 — "끝 + 1" 이 넘쳐 음수 시작을 받지 않는다
            expectValidation(register("k-" + ShopFixtures.unique(), withBatches("""
                    "shipmentBatches": [ { "batchNumber": 1, "positionFrom": 1, "positionTo": 9223372036854775807, "estimatedShipStart": "2026-11-01", "estimatedShipEnd": "2026-11-07" },
                                         { "batchNumber": 2, "positionFrom": -9223372036854775808, "positionTo": null, "estimatedShipStart": "2026-11-08", "estimatedShipEnd": "2026-11-14" } ]
                    """)), "shipmentBatches[1].positionFrom");
            // preorder 보다 엄격하지 않다 — 한 자리짜리 차수(시작 = 종료)도 받는다
            register("k-" + ShopFixtures.unique(), withBatches("""
                    "shipmentBatches": [ { "batchNumber": 1, "positionFrom": 1, "positionTo": 1, "estimatedShipStart": "2026-11-01", "estimatedShipEnd": "2026-11-07" },
                                         { "batchNumber": 2, "positionFrom": 2, "positionTo": null, "estimatedShipStart": "2026-11-08", "estimatedShipEnd": "2026-11-08" } ]
                    """)).andExpect(status().isCreated());
        }

        /**
         * 등록 이벤트 경로에서만 문제가 되는 두 가지는 preorder 보다 엄격하게 막는다(2026-10-04 결정) — 차수가 너무 많으면 이벤트가 SQS 한 메시지를
         * 넘고, DB 가 담지 못하는 날짜 · 시각이면 preorder 가 저장하다 실패해 이벤트가 DLQ 로 간다.
         */
        @Test
        @DisplayName("배송 차수는 최대 100개, 배송 예정일은 1000-01-01 ~ 9999-12-31, 회차 마감은 9999-12-31 까지 — 넘으면 그 칸의 400, 경계는 201. 같은 마이크로초 안의 오픈 · 마감도 400")
        void eventPathLimits() throws Exception {
            register("k-" + ShopFixtures.unique(), withBatches(batches(100, "2026-11-01"))).andExpect(status().isCreated());
            expectValidation(register("k-" + ShopFixtures.unique(), withBatches(batches(101, "2026-11-01"))), "shipmentBatches");

            register("k-" + ShopFixtures.unique(), withBatches(batches(1, "9999-12-31"))).andExpect(status().isCreated());
            register("k-" + ShopFixtures.unique(), withBatches(batches(1, "1000-01-01"))).andExpect(status().isCreated());
            expectValidation(register("k-" + ShopFixtures.unique(), withBatches(batches(1, "+10000-01-01"))), "shipmentBatches[0].estimatedShipStart");
            // 범위 검사가 "종료 ≥ 시작" 보다 먼저다 — 시작이 범위 밖이면 그 칸을 가리킨다
            expectValidation(register("k-" + ShopFixtures.unique(), withBatches("""
                    "shipmentBatches": [ { "batchNumber": 1, "positionFrom": 1, "positionTo": null, "estimatedShipStart": "+10000-01-01", "estimatedShipEnd": "2026-11-01" } ]
                    """)), "shipmentBatches[0].estimatedShipStart");
            expectValidation(register("k-" + ShopFixtures.unique(), withBatches(batches(1, "0999-12-31"))), "shipmentBatches[0].estimatedShipStart");
            expectValidation(register("k-" + ShopFixtures.unique(), withBatches("""
                    "shipmentBatches": [ { "batchNumber": 1, "positionFrom": 1, "positionTo": null, "estimatedShipStart": "2026-11-01", "estimatedShipEnd": "+10000-01-01" } ]
                    """)), "shipmentBatches[0].estimatedShipEnd");

            String farClose = preorderBody("").replace(opensAt.plus(Duration.ofDays(3)).toString(), "+10000-01-01T00:00:00Z");
            expectValidation(register("k-" + ShopFixtures.unique(), farClose), "campaign.closesAt");
            register("k-" + ShopFixtures.unique(), preorderBody("").replace(opensAt.plus(Duration.ofDays(3)).toString(), "9999-12-31T23:59:59.999999Z"))
                    .andExpect(status().isCreated());

            // 같은 마이크로초 안에서 나노초만 다른 오픈 · 마감 — 이벤트에는 마이크로초로 잘려 같은 값이 되므로 preorder 가 거절한다. 등록에서 400
            Instant microsecond = opensAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            String sameMicrosecond = preorderBody("")
                    .replace(opensAt.plus(Duration.ofDays(3)).toString(), microsecond.plusNanos(200).toString())
                    .replace(opensAt.toString(), microsecond.plusNanos(100).toString());
            expectValidation(register("k-" + ShopFixtures.unique(), sameMicrosecond), "campaign.closesAt");
        }

        @Test
        @DisplayName("조합 선택의 축 키는 대소문자를 가리지 않는다 — Color 와 color 가 같이 오면 뒤의 것이 이기지 않고 그 조합의 400")
        void duplicateAxisKeyInSelectionsRejected() throws Exception {
            expectValidation(register("k-" + ShopFixtures.unique(), preorderBody("""
                    "combinations": [ { "selections": { "Color": "블랙", "color": "화이트", "storage": "256GB" } } ],
                    """)), "combinations[0].selections");
            register("k-" + ShopFixtures.unique(), preorderBody("""
                    "combinations": [ { "selections": { "COLOR": "블랙", "storage": "256GB" }, "sku": "B1" } ],
                    """)).andExpect(status().isCreated());   // 대조군 — 한 번이면 대소문자와 상관없이 받는다
        }

        @Test
        @DisplayName("정규화로 늘어난 글자도 칼럼을 넘으면 그 칸의 400(500 이 아니다) — NFC 의 옵션 값 · 상세 영역 이름 60자, 소문자로 접은 축 키 40자")
        void normalizedTextMustFitColumns() throws Exception {
            String expands = "\u0958".repeat(60);   // 받은 글자는 60자지만 NFC 는 한 글자를 두 글자(U+0915 U+093C)로 푼다 — 120자
            String fits = "\u0958".repeat(30);
            expectValidation(register("k-" + ShopFixtures.unique(), singleAxisBody("color", expands)), "optionAxes[0].values[0].value");
            register("k-" + ShopFixtures.unique(), singleAxisBody("color", fits)).andExpect(status().isCreated());   // 대조군 — 정규화해서 60자
            expectValidation(register("k-" + ShopFixtures.unique(), detailSectionBody(expands)), "images.detail[0].section");
            register("k-" + ShopFixtures.unique(), detailSectionBody(fits)).andExpect(status().isCreated());
            // 축 키는 소문자로 접어 저장한다 — İ(U+0130)는 접으면 두 글자(i + U+0307)라 40자가 80자가 된다
            expectValidation(register("k-" + ShopFixtures.unique(), singleAxisBody("\u0130".repeat(40), "블랙")), "optionAxes[0].key");
            register("k-" + ShopFixtures.unique(), singleAxisBody("I".repeat(40), "블랙")).andExpect(status().isCreated());   // 대조군 — 접어도 40자
        }

        @Test
        @DisplayName("정수 칸은 소수를 받지 않는다 — 재고 1.9 · 1.0 · 차수 번호 1.7 · 시작 순번 1.2 는 그 칸의 400(잘라서 1 로 저장하지 않는다)")
        void integerFieldsRejectFractions() throws Exception {
            expectValidation(register("k-" + ShopFixtures.unique(), inStockBody("9000", "1.9")), "combinations[0].stock");
            expectValidation(register("k-" + ShopFixtures.unique(), inStockBody("9000", "1.0")), "combinations[0].stock");   // 값이 정수여도 소수 표기면 400
            register("k-" + ShopFixtures.unique(), inStockBody("9000", "2")).andExpect(status().isCreated());   // 대조군
            expectValidation(register("k-" + ShopFixtures.unique(), withBatches("""
                    "shipmentBatches": [ { "batchNumber": 1.7, "positionFrom": 1, "positionTo": null, "estimatedShipStart": "2026-11-01", "estimatedShipEnd": "2026-11-07" } ]
                    """)), "shipmentBatches[0].batchNumber");
            expectValidation(register("k-" + ShopFixtures.unique(), withBatches("""
                    "shipmentBatches": [ { "batchNumber": 1, "positionFrom": 1.2, "positionTo": null, "estimatedShipStart": "2026-11-01", "estimatedShipEnd": "2026-11-07" } ]
                    """)), "shipmentBatches[0].positionFrom");
        }

        @Test
        @DisplayName("금액은 decimal(12,0) 안 — 기본가 · 추가금 · 보증 추가금이 999,999,999,999 를 넘거나 기본가 + 추가금이 넘으면 그 칸의 400(500 이 아니다). 조합 가격은 받지 않는다")
        void amountsAboveColumnLimitAreFieldErrors() throws Exception {
            String max = "999999999999";
            register("k-" + ShopFixtures.unique(), inStockBody(max, "3")).andExpect(status().isCreated());   // 경계는 들어간다
            expectValidation(register("k-" + ShopFixtures.unique(), inStockBody("1000000000000", "3")), "basePrice");
            // 조합 가격은 기본가 + 추가금이라 받지 않는다(2026-10-06) — 모르는 칸 400
            expectValidation(register("k-" + ShopFixtures.unique(), inStockBody("9000", "3")
                    .replace("\"stock\": 3", "\"stock\": 3, \"price\": 5000")), "combinations[0].price");
            expectValidation(register("k-" + ShopFixtures.unique(), inStockBody("9000", "3")
                    .replace("\"visible\": true,", "\"visible\": true, \"warranty\": { \"offered\": true, \"surcharge\": 1000000000000 },")), "warranty.surcharge");

            String withStorage = inStockBody(max, "3")
                    .replace("\"visible\": true,", "\"visible\": true, \"optionAxes\": [ { \"key\": \"storage\", \"label\": \"용량\", \"values\": [ { \"value\": \"512GB\", \"surcharge\": %s } ] } ],")
                    .replace("\"selections\": {}", "\"selections\": { \"storage\": \"512GB\" }");
            expectValidation(register("k-" + ShopFixtures.unique(), withStorage.formatted("1")), "combinations[storage=512GB]");
            expectValidation(register("k-" + ShopFixtures.unique(), withStorage.formatted("1000000000000")), "optionAxes[0].values[0].surcharge");
        }

        /** 축 없는 일반 상품 하나(조합 하나). 조합 칸 경로는 선택 키로 만든다 — 축이 없으면 combinations[], storage 축이면 combinations[storage=512GB]. */
        private String inStockBody(String basePrice, String stock) {
            return """
                    { "categoryId": %d, "saleMode": "IN_STOCK", "title": "Limit", "visible": true, "basePrice": %s,
                      "combinations": [ { "selections": {}, "stock": %s } ] }
                    """.formatted(categoryId, basePrice, stock);
        }

        /** 축 하나(값 하나)의 일반 상품. 표시명이 값 그대로라 표시명 상한(120)보다 값 칼럼(60)이 먼저 걸린다. */
        private String singleAxisBody(String key, String value) {
            return """
                    { "categoryId": %d, "saleMode": "IN_STOCK", "title": "Limit", "visible": true, "basePrice": 1000,
                      "optionAxes": [ { "key": "%s", "label": "축", "values": [ { "value": "%s" } ] } ],
                      "combinations": [ { "selections": { "%s": "%s" }, "sku": "C1", "stock": 1 } ] }
                    """.formatted(categoryId, key, value, key, value);
        }

        private String detailSectionBody(String section) {
            return """
                    { "categoryId": %d, "saleMode": "IN_STOCK", "title": "Limit", "visible": true, "basePrice": 1000,
                      "images": { "detail": [ { "section": "%s", "items": [ { "url": "https://img/1.jpg" } ] } ] },
                      "combinations": [ { "selections": {}, "stock": 1 } ] }
                    """.formatted(categoryId, section);
        }

        /** 1 번부터 이어지는 한 자리 차수 n 개(마지막은 상한 없음). 배송 예정일은 모두 같은 날. */
        private String batches(int count, String shipDate) {
            StringBuilder json = new StringBuilder("\"shipmentBatches\": [ ");
            for (int i = 1; i <= count; i++) {
                json.append(i > 1 ? ", " : "")
                        .append("{ \"batchNumber\": %d, \"positionFrom\": %d, \"positionTo\": %s, \"estimatedShipStart\": \"%s\", \"estimatedShipEnd\": \"%s\" }"
                                .formatted(i, i, i == count ? "null" : String.valueOf(i), shipDate, shipDate));
            }
            return json.append(" ]\n").toString();
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
