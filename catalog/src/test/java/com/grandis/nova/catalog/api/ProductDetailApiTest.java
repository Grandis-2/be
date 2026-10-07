package com.grandis.nova.catalog.api;

import com.grandis.nova.catalog.support.CatalogIntegrationTest;
import com.grandis.nova.catalog.support.ShopFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@CatalogIntegrationTest
@AutoConfigureMockMvc
class ProductDetailApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration HOUR = Duration.ofHours(1);

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;

    ShopFixtures fixtures;
    Long categoryId;
    /** visibleInStock 으로 만든 일반 상품. 요청 직전에 준비(재고 행)를 넣는다 — 시험이 옵션을 다 넣은 뒤여야 해서. */
    List<Long> inStockProducts;

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
        categoryId = fixtures.category();
        inStockProducts = new ArrayList<>();
    }

    @Nested
    @DisplayName("응답 모양")
    class Shape {

        @Test
        @DisplayName("사전예약 상세 — 회차 · 접수 단계 · 옵션 축과 값 · 옵션마다 선택값 · 색상별 사진과 상세 영역 · 보증")
        void preorderDetail() throws Exception {
            Instant now = Instant.now();
            Long productId = fixtures.product(categoryId, "PREORDER", "ACTIVE", "Nova 1", null);
            jdbcTemplate.update("UPDATE products SET description = '설명', base_price = 1000000, warranty_offered = 1, warranty_surcharge = 150000 WHERE id = ?", productId);
            fixtures.registration(productId);
            fixtures.campaign(productId, now.minus(HOUR), now.plus(HOUR));
            ShopFixtures.AxisRef storage = fixtures.axis(productId, "storage", 1);
            ShopFixtures.AxisRef color = fixtures.axis(productId, "color", "색상", 0);
            // 값은 자리 역순으로 넣어 넣은 순과 갈라 놓는다 — 응답은 문서 배열 순이어야 한다
            String white = fixtures.value(color, "화이트", 1);
            String black = fixtures.value(color, "블랙", 0);
            String gb256 = fixtures.value(storage, "256 GB", "256GB", new BigDecimal("200000"), 0);
            Long black256 = fixtures.optionWithAttributes(productId, "ACTIVE", new BigDecimal("1200000"), "블랙 / 256GB",
                    "{\"color\":\"블랙\",\"storage\":\"256GB\"}");
            fixtures.selection(productId, black256, color, black);
            fixtures.selection(productId, black256, storage, gb256);
            Long whitePaused = fixtures.option(productId, "PAUSED", new BigDecimal("1300000"));
            fixtures.selection(productId, whitePaused, color, white);
            fixtures.image(productId, "GALLERY", "화이트", 0, true, "https://img/white-1.jpg");
            fixtures.image(productId, "GALLERY", "블랙", 1, true, "https://img/black-primary.jpg");
            fixtures.image(productId, "GALLERY", "블랙", 0, false, "https://img/black-0.jpg");
            fixtures.image(productId, "DETAIL", "제품사양", 0, true, "https://img/spec.jpg");

            JsonNode data = data(anonymous(productId).andExpect(status().isOk()));
            assertThat(data.get("productId").asLong()).isEqualTo(productId);
            assertThat(data.get("categoryId").asLong()).isEqualTo(categoryId);
            assertThat(data.get("saleMode").asString()).isEqualTo("PREORDER");
            assertThat(data.get("title").asString()).isEqualTo("Nova 1");
            assertThat(data.get("description").asString()).isEqualTo("설명");
            assertThat(data.get("status").asString()).isEqualTo("ACTIVE");
            assertThat(data.get("visible").asBoolean()).isTrue();
            assertThat(data.get("basePrice").decimalValue()).isEqualByComparingTo("1000000");
            assertThat(data.get("warranty").get("offered").asBoolean()).isTrue();
            assertThat(data.get("warranty").get("surcharge").decimalValue()).isEqualByComparingTo("150000");
            assertThat(data.get("soldOut").asBoolean()).isFalse();
            assertThat(data.get("sellable").asBoolean()).isTrue();
            assertThat(data.get("campaign").get("status").asString()).isEqualTo("OPEN");
            assertThat(data.get("campaign").get("opensAt").asString()).isNotBlank();
            assertThat(data.has("shipmentBatches")).isFalse();
            // 썸네일: 첫 색상(블랙)의 첫 장 — 대표 표시(black-primary)가 아니다
            assertThat(data.get("imageUrl").asString()).isEqualTo("https://img/black-0.jpg");

            JsonNode axes = data.get("optionAxes");
            assertThat(texts(axes, "key")).containsExactly("color", "storage");
            assertThat(axes.get(0).get("label").asString()).isEqualTo("색상");
            assertThat(texts(axes.get(0).get("values"), "normalizedValue")).containsExactly("블랙", "화이트");
            assertThat(axes.get(1).get("values").get(0).get("value").asString()).isEqualTo("256 GB");
            assertThat(axes.get(1).get("values").get(0).get("surcharge").decimalValue()).isEqualByComparingTo("200000");

            JsonNode variants = data.get("variants");
            assertThat(variants).hasSize(2);
            JsonNode first = variants.get(0);
            assertThat(first.get("variantId").asLong()).isEqualTo(black256);
            assertThat(first.get("title").asString()).isEqualTo("블랙 / 256GB");
            assertThat(first.get("price").decimalValue()).isEqualByComparingTo("1200000");
            assertThat(first.get("filterAttributes").get("storage").asString()).isEqualTo("256GB");
            assertThat(first.has("displayAttributes")).as("표시 속성 칸은 없앴다 — 축 → 값은 selections").isFalse();
            assertThat(first.get("selections").get("color").asString()).isEqualTo("블랙");
            assertThat(first.get("selections").get("storage").asString()).isEqualTo("256GB");
            assertThat(first.get("availableQuantity").isNull()).as("사전예약은 무제한 접수").isTrue();
            assertThat(variants.get(1).get("status").asString()).isEqualTo("PAUSED");
            assertThat(variants.get(1).get("filterAttributes").get("color").asString()).isEqualTo("화이트");
            assertThat(variants.get(1).get("selections").get("color").asString()).isEqualTo("화이트");

            JsonNode gallery = data.get("images").get("gallery");
            assertThat(texts(gallery, "bundleKey")).containsExactly("블랙", "화이트");
            assertThat(texts(gallery.get(0).get("items"), "url")).containsExactly("https://img/black-0.jpg", "https://img/black-primary.jpg");
            assertThat(gallery.get(0).get("items").get(1).get("primary").asBoolean()).isTrue();
            JsonNode detail = data.get("images").get("detail");
            assertThat(texts(detail, "bundleKey")).containsExactly("제품사양");
        }

        @Test
        @DisplayName("썸네일 · 사진 묶음은 관리자가 넣은 색상 순서를 따른다 — 사전순(블랙 < 화이트)이 아니다")
        void thumbnailFollowsColorOrderNotAlphabet() throws Exception {
            Long productId = visibleInStock();
            fixtures.option(productId, "PAUSED");
            fixtures.image(productId, "GALLERY", "화이트", 0, true, "https://img/white.jpg");
            fixtures.image(productId, "GALLERY", "블랙", 0, true, "https://img/black.jpg");

            JsonNode data = data(anonymous(productId).andExpect(status().isOk()));
            assertThat(data.get("imageUrl").asString()).isEqualTo("https://img/white.jpg");
            assertThat(texts(data.get("images").get("gallery"), "bundleKey")).containsExactly("화이트", "블랙");
        }

        @Test
        @DisplayName("일반 상품 상세 — campaign 은 null, 옵션마다 가용 수량(재고 행 없으면 0), 품절 판정")
        void inStockDetail() throws Exception {
            Long productId = visibleInStock();
            Long stocked = fixtures.option(productId, "ACTIVE", new BigDecimal("1000"));
            fixtures.inventory(stocked, 10, 3, 2);
            Long noRow = fixtures.option(productId, "ACTIVE", new BigDecimal("1000"));
            Long depleted = fixtures.option(productId, "ACTIVE", new BigDecimal("1000"));
            fixtures.inventory(depleted, 5, 5, 0);

            JsonNode data = data(anonymous(productId).andExpect(status().isOk()));
            assertThat(data.get("campaign").isNull()).isTrue();
            assertThat(data.get("soldOut").asBoolean()).isFalse();
            assertThat(quantityOf(data, stocked)).isEqualTo(5);
            assertThat(quantityOf(data, noRow)).isEqualTo(0);
            assertThat(quantityOf(data, depleted)).isEqualTo(0);
            assertThat(data.get("images").get("gallery")).isEmpty();
            assertThat(data.get("imageUrl").isNull()).isTrue();

            jdbcTemplate.update("UPDATE option_inventories SET stock_sold = 7 WHERE option_id = ?", stocked);
            assertThat(data(anonymous(productId)).get("soldOut").asBoolean()).as("가용 재고가 전부 0 이면 품절").isTrue();

            // 재고가 있는 건 판매 중지 옵션뿐 — 품절이다. 판정이 옵션 상태를 봐야 한다
            Long pausedWithStock = fixtures.option(productId, "PAUSED", new BigDecimal("1000"));
            fixtures.inventory(pausedWithStock, 9, 0, 0);
            assertThat(data(anonymous(productId)).get("soldOut").asBoolean()).as("판매 중지 옵션의 재고는 안 센다").isTrue();
        }

        @Test
        @DisplayName("목록의 soldOut 과 상세의 soldOut 은 같은 데이터에서 같다 — 규칙이 SQL 과 Java 두 곳에 있어 어긋남을 잡는다")
        void listAndDetailAgreeOnSoldOut() throws Exception {
            String tag = "s" + ShopFixtures.unique().replace("-", "");
            List<Long> ids = new ArrayList<>();
            Long stocked = visibleInStock(tag);
            fixtures.inventory(fixtures.option(stocked, "ACTIVE", new BigDecimal("1")), 1, 0, 0);
            Long depleted = visibleInStock(tag);
            fixtures.inventory(fixtures.option(depleted, "ACTIVE", new BigDecimal("1")), 1, 1, 0);
            // 판매 중 옵션은 재고 행이 없고, 판매 중지 옵션의 재고 행이 상품을 준비 상태로 만든다 — "재고 행 없음 = 판매 불가" 를 상품이 노출된 채로 본다
            Long noRow = visibleInStock(tag);
            fixtures.option(noRow, "ACTIVE", new BigDecimal("1"));
            fixtures.inventory(fixtures.option(noRow, "PAUSED", new BigDecimal("1")), 0, 0, 0);
            Long pausedOnly = visibleInStock(tag);
            fixtures.inventory(fixtures.option(pausedOnly, "PAUSED", new BigDecimal("1")), 5, 0, 0);
            // 옵션이 없는 일반 상품은 준비될 수 없어 목록에 없다(등록 API 로는 생기지 않는다 — 조합 0 은 400)
            Long noOptions = fixtures.product(categoryId, "IN_STOCK", "ACTIVE", "Nova Book", tag);
            fixtures.registration(noOptions);
            Instant now = Instant.now();
            Long preorderActive = visiblePreorder(tag, now.minus(HOUR), now.plus(HOUR));
            fixtures.option(preorderActive, "ACTIVE", new BigDecimal("1"));
            Long preorderPausedOnly = visiblePreorder(tag, now.minus(HOUR), now.plus(HOUR));
            fixtures.option(preorderPausedOnly, "PAUSED", new BigDecimal("1"));
            ids.addAll(List.of(stocked, depleted, noRow, pausedOnly, preorderActive, preorderPausedOnly));

            inStockProducts.forEach(fixtures::stockReady);
            JsonNode items = JSON.readTree(mockMvc.perform(get("/api/v1/products").param("q", tag).param("size", "100"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).at("/data/items");
            assertThat(items).hasSize(ids.size());
            assertThat(texts(items, "productId")).doesNotContain(String.valueOf(noOptions));
            for (JsonNode item : items) {
                Long productId = item.get("productId").asLong();
                JsonNode detail = data(anonymous(productId).andExpect(status().isOk()));
                assertThat(detail.get("soldOut").asBoolean()).as("soldOut %d", productId).isEqualTo(item.get("soldOut").asBoolean());
                assertThat(detail.get("sellable").asBoolean()).as("sellable %d", productId).isEqualTo(item.get("sellable").asBoolean());
            }
            // 대조군 — 둘이 실제로 참과 거짓을 모두 낸다
            assertThat(texts(items, "soldOut")).contains("true", "false");
            assertThat(texts(items, "sellable")).contains("true", "false");
        }
    }

    @Nested
    @DisplayName("노출 규칙")
    class Visibility {

        @Test
        @DisplayName("비공개 · 준비 전(재고 행 없음)은 누구에게나 404 NOT_FOUND — 관리자도. 미리보기는 관리자 상세로(2026-09-29 결정)")
        void hiddenIsNotFoundForEveryone() throws Exception {
            Long hidden = visibleInStock();
            fixtures.option(hidden, "ACTIVE");
            jdbcTemplate.update("UPDATE products SET visible = 0 WHERE id = ?", hidden);
            Long incomplete = fixtures.product(categoryId, "IN_STOCK", "ACTIVE", "준비 전", null);
            fixtures.option(incomplete, "ACTIVE");
            fixtures.registration(incomplete, ShopFixtures.unique());

            for (Long productId : List.of(hidden, incomplete)) {
                anonymous(productId).andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
                mockMvc.perform(get("/api/v1/products/{id}", productId).with(user("657").roles("USER")))
                        .andExpect(status().isNotFound());
                // 관리자 토큰도 404 — 공개 경로는 폐기 조회 실패에 열리는 경로라 여기서 관리자를 더 믿지 않는다
                mockMvc.perform(get("/api/v1/products/{id}", productId).with(user("admin").roles("ADMIN")))
                        .andExpect(status().isNotFound());
            }
        }

        @Test
        @DisplayName("판매 중지는 200 에 상태 그대로, 마감 뒤 120시간이 지난 사전예약도 직접 링크로는 보인다")
        void pausedAndLongClosedAreStillViewable() throws Exception {
            Long paused = visibleInStock();
            fixtures.option(paused, "ACTIVE");
            jdbcTemplate.update("UPDATE products SET status = 'PAUSED' WHERE id = ?", paused);
            assertThat(data(anonymous(paused).andExpect(status().isOk())).get("status").asString()).isEqualTo("PAUSED");

            Instant now = Instant.now();
            Long longClosed = fixtures.product(categoryId, "PREORDER", "ACTIVE", "오래 전 마감", null);
            fixtures.registration(longClosed);
            fixtures.campaign(longClosed, now.minus(HOUR.multipliedBy(200)), now.minus(HOUR.multipliedBy(190)));
            JsonNode data = data(anonymous(longClosed).andExpect(status().isOk()));
            assertThat(data.get("campaign").get("status").asString()).isEqualTo("CLOSED");
        }

        @Test
        @DisplayName("없는 상품은 404 NOT_FOUND — 비공개와 같은 응답이라 회원이 둘을 가르지 못한다")
        void unknownIsNotFound() throws Exception {
            anonymous(999_999_999L).andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        }
    }

    @Nested
    @DisplayName("옵션 상세")
    class VariantDetail {

        @Test
        @DisplayName("상품 소속 옵션만 200, 다른 상품의 옵션 · 비공개 상품의 옵션은 404")
        void variantMustBelongToViewableProduct() throws Exception {
            Long productId = visibleInStock();
            ShopFixtures.AxisRef axis = fixtures.axis(productId, "color", 0);
            String black = fixtures.value(axis, "블랙", 0);
            Long option = fixtures.optionWithAttributes(productId, "ACTIVE", new BigDecimal("777"), "블랙",
                    "{\"color\":\"블랙\"}");
            fixtures.selection(productId, option, axis, black);
            fixtures.inventory(option, 3, 1, 0);
            Long other = visibleInStock();
            Long otherOption = fixtures.option(other, "ACTIVE", new BigDecimal("1"));
            Long hidden = visibleInStock();
            jdbcTemplate.update("UPDATE products SET visible = 0 WHERE id = ?", hidden);
            Long hiddenOption = fixtures.option(hidden, "ACTIVE", new BigDecimal("1"));

            JsonNode data = data(mockMvc.perform(get("/api/v1/products/{p}/variants/{v}", productId, option))
                    .andExpect(status().isOk()));
            assertThat(data.get("variantId").asLong()).isEqualTo(option);
            assertThat(data.get("price").decimalValue()).isEqualByComparingTo("777");
            assertThat(data.get("availableQuantity").asInt()).isEqualTo(2);
            assertThat(data.get("selections").get("color").asString()).isEqualTo("블랙");
            assertThat(data.get("filterAttributes").get("color").asString()).isEqualTo("블랙");
            assertThat(data.has("displayAttributes")).isFalse();

            mockMvc.perform(get("/api/v1/products/{p}/variants/{v}", productId, otherOption))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
            mockMvc.perform(get("/api/v1/products/{p}/variants/{v}", hidden, hiddenOption))
                    .andExpect(status().isNotFound());
            mockMvc.perform(get("/api/v1/products/{p}/variants/{v}", hidden, hiddenOption).with(user("admin").roles("ADMIN")))
                    .andExpect(status().isNotFound());
        }
    }

    private Long visibleInStock() {
        return visibleInStock(null);
    }

    private Long visibleInStock(String tags) {
        Long productId = fixtures.product(categoryId, "IN_STOCK", "ACTIVE", "Nova Book", tags);
        fixtures.registration(productId);
        inStockProducts.add(productId);
        return productId;
    }

    private Long visiblePreorder(String tags, Instant opensAt, Instant closesAt) {
        Long productId = fixtures.product(categoryId, "PREORDER", "ACTIVE", "Nova 1", tags);
        fixtures.registration(productId);
        fixtures.campaign(productId, opensAt, closesAt);
        return productId;
    }

    private ResultActions anonymous(Long productId) throws Exception {
        inStockProducts.forEach(fixtures::stockReady);
        return mockMvc.perform(get("/api/v1/products/{id}", productId));
    }

    private static JsonNode data(ResultActions actions) throws Exception {
        return JSON.readTree(actions.andReturn().getResponse().getContentAsString()).get("data");
    }

    private static int quantityOf(JsonNode data, Long variantId) {
        for (JsonNode variant : data.get("variants")) {
            if (variant.get("variantId").asLong() == variantId) {
                return variant.get("availableQuantity").asInt();
            }
        }
        throw new AssertionError("variant " + variantId + " missing");
    }

    private static List<String> texts(JsonNode nodes, String field) {
        List<String> out = new ArrayList<>();
        for (JsonNode node : nodes) {
            out.add(node.get(field).asString());
        }
        return out;
    }
}
