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
import org.springframework.util.LinkedMultiValueMap;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 관리자 목록은 노출 규칙이 없어 같은 컨테이너의 다른 시험 상품이 전부 보인다. 시험마다 고유한 tags 를 붙이고 q 로 골라내 자기 상품만 본다.
 */
@CatalogIntegrationTest
@AutoConfigureMockMvc
class AdminProductListApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration HOUR = Duration.ofHours(1);
    private static final String PATH = "/api/v1/admin/products";

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;

    ShopFixtures fixtures;
    String tag;
    Long categoryId;

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
        tag = "t" + ShopFixtures.unique().replace("-", "");
        categoryId = fixtures.category();
    }

    @Nested
    @DisplayName("범위 · 관리자 칸")
    class Scope {

        @Test
        @DisplayName("노출 규칙이 없다 — 등록 없음 · 미완료 · 막힘 · 비공개 · 판매 중지 · 오래된 마감이 전부 나오고 공개 여부 · 등록 완료 · 막힘 사유 · 옵션 수가 실린다")
        void everyProductIsListedWithAdminFields() throws Exception {
            Long shown = completed("보임", "IN_STOCK");
            fixtures.option(shown, "ACTIVE", new BigDecimal("1000"));
            fixtures.option(shown, "PAUSED", new BigDecimal("900"));
            // 등록 API 이전에 들어온 행 — 기록이 없고 visible 은 칸 기본값 1
            Long noRegistration = fixtures.product(categoryId, "IN_STOCK", "ACTIVE", "등록 없음", tag);
            // 등록 중인 상품은 등록 서비스가 visible=0 으로 둔다 — 런타임에 있는 조합만 픽스처로 만든다
            Long incomplete = fixtures.product(categoryId, "IN_STOCK", "ACTIVE", "미완료", tag);
            fixtures.registration(incomplete, ShopFixtures.unique());
            jdbcTemplate.update("UPDATE products SET visible = 0 WHERE id = ?", incomplete);
            Long blocked = fixtures.product(categoryId, "PREORDER", "ACTIVE", "막힘", tag);
            fixtures.registration(blocked, ShopFixtures.unique());
            jdbcTemplate.update("UPDATE products SET visible = 0 WHERE id = ?", blocked);
            jdbcTemplate.update("UPDATE product_registrations SET blocked_reason = 'OPENED_BEFORE_COMPLETE' WHERE product_id = ?", blocked);
            Long hidden = completed("비공개", "IN_STOCK");
            jdbcTemplate.update("UPDATE products SET visible = 0 WHERE id = ?", hidden);
            Long paused = completed("판매 중지", "IN_STOCK");
            jdbcTemplate.update("UPDATE products SET status = 'PAUSED' WHERE id = ?", paused);
            Instant now = Instant.now();
            Long longClosed = completed("오래된 마감", "PREORDER");
            fixtures.campaign(longClosed, now.minus(HOUR.multipliedBy(200)), now.minus(HOUR.multipliedBy(190)));

            JsonNode page = data(perform());
            JsonNode items = page.get("items");
            assertThat(page.get("total").asLong()).as("건수도 노출 규칙 없이 전부(등록 없는 행 포함)").isEqualTo(7);
            assertThat(ids(items)).as("productId 내림차순, 전부")
                    .containsExactly(longClosed, paused, hidden, blocked, incomplete, noRegistration, shown);
            assertThat(find(items, shown).get("visible").asBoolean()).isTrue();
            assertThat(find(items, shown).get("registrationCompleted").asBoolean()).isTrue();
            assertThat(find(items, shown).get("blockedReason").isNull()).isTrue();
            assertThat(find(items, shown).get("optionCount").asInt()).as("판매 중지 옵션도 센다").isEqualTo(2);
            assertThat(find(items, noRegistration).get("optionCount").asInt()).isZero();
            // visible 은 칸 그대로다 — 등록 없는 행은 visible=true 이면서 registrationCompleted=false. 상세의 product.visible 과 같은 정의
            assertThat(find(items, noRegistration).get("visible").asBoolean()).isTrue();
            assertThat(find(items, noRegistration).get("registrationCompleted").asBoolean()).isFalse();
            JsonNode detail = JSON.readTree(mockMvc.perform(get(PATH + "/{id}", noRegistration).with(user("admin").roles("ADMIN")))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("data");
            assertThat(detail.get("product").get("visible").asBoolean()).as("목록과 상세의 visible 은 같은 상품에서 같다").isTrue();
            assertThat(detail.get("registration").isNull()).isTrue();
            assertThat(find(items, incomplete).get("visible").asBoolean()).isFalse();
            assertThat(find(items, incomplete).get("registrationCompleted").asBoolean()).isFalse();
            assertThat(find(items, blocked).get("blockedReason").asString()).isEqualTo("OPENED_BEFORE_COMPLETE");
            assertThat(find(items, hidden).get("visible").asBoolean()).isFalse();
            assertThat(find(items, hidden).get("registrationCompleted").asBoolean()).isTrue();
            assertThat(find(items, paused).get("status").asString()).isEqualTo("PAUSED");
            assertThat(find(items, longClosed).get("preorderStatus").asString()).isEqualTo("CLOSED");
            assertThat(find(items, longClosed).get("closesAt").asString()).isNotBlank();
        }

        @Test
        @DisplayName("등록 API 로 visible: true 를 보내도 등록이 끝나기 전에는 목록의 visible 이 false 다 — 고른 값은 등록 기록이 들고 있다")
        void visibleIsFalseUntilRegistrationCompletes() throws Exception {
            String body = """
                    { "categoryId": %d, "saleMode": "IN_STOCK", "title": "등록 중", "tags": "%s", "visible": true, "basePrice": 10000,
                      "combinations": [ { "selections": {}, "stock": 3 } ] }
                    """.formatted(categoryId, tag);
            ResultActions created = mockMvc.perform(post(PATH).header("Idempotency-Key", "k-" + ShopFixtures.unique())
                    .contentType(MediaType.APPLICATION_JSON).content(body).with(user("admin").roles("ADMIN")))
                    .andExpect(status().isCreated());
            long productId = JSON.readTree(created.andReturn().getResponse().getContentAsString())
                    .get("data").get("registration").get("productId").asLong();
            assertThat(jdbcTemplate.queryForObject("SELECT requested_visible FROM product_registrations WHERE product_id = ?", Boolean.class, productId))
                    .as("고른 값은 등록 기록에").isTrue();

            JsonNode item = find(list(), productId);
            assertThat(item.get("visible").asBoolean()).isFalse();
            assertThat(item.get("registrationCompleted").asBoolean()).isFalse();
            assertThat(item.get("optionCount").asInt()).isEqualTo(1);
        }

        @Test
        @DisplayName("회원 목록과 같은 칸 — 최저가 · 판매 가능 · 품절 · 대표 사진 — 이 같은 규칙으로 실린다")
        void sharesTheMemberListingColumns() throws Exception {
            Long productId = completed("칸", "IN_STOCK");
            Long paused = fixtures.option(productId, "PAUSED", new BigDecimal("100"));
            Long soldOut = fixtures.option(productId, "ACTIVE", new BigDecimal("500"));
            fixtures.inventory(soldOut, 10, 4, 6);
            fixtures.image(productId, "GALLERY", "", 0, true, "https://img/main.jpg");

            JsonNode item = find(list(), productId);
            assertThat(item.get("minPrice").decimalValue()).as("판매 중지 옵션 제외, 품절 포함").isEqualByComparingTo("500");
            assertThat(item.get("sellable").asBoolean()).isTrue();
            assertThat(item.get("soldOut").asBoolean()).isTrue();
            assertThat(item.get("imageUrl").asString()).isEqualTo("https://img/main.jpg");
            assertThat(item.get("saleMode").asString()).isEqualTo("IN_STOCK");
            assertThat(paused).isNotNull();
        }
    }

    @Nested
    @DisplayName("필터 · 페이징")
    class FilterAndPaging {

        @Test
        @DisplayName("saleMode · status 로 좁히고, q 는 상품명 · tags 부분 일치(대소문자 무시 · 와일드카드는 문자)")
        void filters() throws Exception {
            Long inStock = completed("Nova Case", "IN_STOCK");
            Long preorder = completed("Nova Phone", "PREORDER");
            Long paused = completed("Nova Pad 100%", "IN_STOCK");
            jdbcTemplate.update("UPDATE products SET status = 'PAUSED' WHERE id = ?", paused);

            JsonNode preorderPage = data(perform("saleMode", "PREORDER"));
            assertThat(ids(preorderPage.get("items"))).containsExactly(preorder);
            assertThat(preorderPage.get("total").asLong()).as("건수도 같은 필터를 탄다").isEqualTo(1);
            JsonNode pausedPage = data(perform("status", "PAUSED"));
            assertThat(ids(pausedPage.get("items"))).containsExactly(paused);
            assertThat(pausedPage.get("total").asLong()).isEqualTo(1);
            assertThat(pausedPage.get("hasNext").asBoolean()).isFalse();
            JsonNode activePage = data(perform("status", "ACTIVE", "size", "1"));
            assertThat(ids(activePage.get("items"))).containsExactly(preorder);
            assertThat(activePage.get("total").asLong()).isEqualTo(2);
            assertThat(activePage.get("hasNext").asBoolean()).isTrue();
            // q 는 회원 목록과 같은 정규화 — 공백 접기 · NFC(맥 한글 입력기의 NFD 도 찾는다)
            Long hangul = completed("케이스", "IN_STOCK");
            assertThat(ids(list("q", "NOVA  case"))).contains(inStock).doesNotContain(preorder, paused);
            assertThat(ids(list("q", java.text.Normalizer.normalize("케이스", java.text.Normalizer.Form.NFD)))).contains(hangul);
            assertThat(ids(list("q", "nova pa"))).as("q 를 덮으면 tag 로 안 거르니 다른 시험 상품이 섞일 수 있다 — 포함만 본다").contains(paused);
            assertThat(ids(list("q", "100%"))).contains(paused).doesNotContain(inStock, preorder);
            perform("status", "SOLD").andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.error.details.violations[0].field").value("status"));
        }

        @Test
        @DisplayName("page · size · total · hasNext, size 는 1~100 · page 는 0 이상")
        void paging() throws Exception {
            Long first = completed("1", "IN_STOCK");
            Long second = completed("2", "IN_STOCK");
            Long third = completed("3", "IN_STOCK");

            JsonNode page0 = data(perform("size", "2", "page", "0"));
            assertThat(page0.get("total").asLong()).isEqualTo(3);
            assertThat(page0.get("hasNext").asBoolean()).isTrue();
            assertThat(ids(page0.get("items"))).containsExactly(third, second);
            JsonNode page1 = data(perform("size", "2", "page", "1"));
            assertThat(page1.get("hasNext").asBoolean()).isFalse();
            assertThat(ids(page1.get("items"))).containsExactly(first);

            perform("size", "0").andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.details.violations[0].field").value("size"));
            perform("size", "101").andExpect(status().isBadRequest());
            perform("page", "-1").andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.details.violations[0].field").value("page"));
        }
    }

    @Test
    @DisplayName("관리자만 — 익명 401, 회원 403")
    void adminOnly() throws Exception {
        mockMvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(PATH).with(user("657").roles("USER"))).andExpect(status().isForbidden());
    }

    private Long completed(String title, String saleMode) {
        Long productId = fixtures.product(categoryId, saleMode, "ACTIVE", title, tag);
        fixtures.completeRegistration(productId);
        return productId;
    }

    private JsonNode list(String... params) throws Exception {
        return data(perform(params)).get("items");
    }

    /** 기본은 q=tag · size=100. 같은 이름을 넘기면 기본을 덮어쓴다. */
    private ResultActions perform(String... params) throws Exception {
        LinkedMultiValueMap<String, String> query = new LinkedMultiValueMap<>();
        for (int i = 0; i < params.length; i += 2) {
            query.add(params[i], params[i + 1]);
        }
        query.putIfAbsent("q", List.of(tag));
        query.putIfAbsent("size", List.of("100"));
        MockHttpServletRequestBuilder request = get(PATH).with(user("admin").roles("ADMIN"));
        query.forEach((name, values) -> request.queryParam(name, values.toArray(String[]::new)));
        return mockMvc.perform(request);
    }

    private static JsonNode data(ResultActions actions) throws Exception {
        return JSON.readTree(actions.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("data");
    }

    private static List<Long> ids(JsonNode items) {
        List<Long> ids = new ArrayList<>();
        for (JsonNode item : items) {
            ids.add(item.get("productId").asLong());
        }
        return ids;
    }

    private static JsonNode find(JsonNode items, Long productId) {
        for (JsonNode item : items) {
            if (item.get("productId").asLong() == productId) {
                return item;
            }
        }
        throw new AssertionError("product " + productId + " not in " + items);
    }
}
