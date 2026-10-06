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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.util.LinkedMultiValueMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 같은 컨테이너에 다른 시험의 상품이 남아 있을 수 있다. 등록이 끝나지 않은 상품은 어차피 안 보이지만,
 * 시험마다 고유한 tags 를 붙이고 q 로 골라내 자기 상품만 본다.
 */
@CatalogIntegrationTest
@AutoConfigureMockMvc
class ProductListApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration HOUR = Duration.ofHours(1);

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;

    ShopFixtures fixtures;
    String tag;
    Long categoryId;
    /** visibleInStock 으로 만든 일반 상품. 목록을 부르기 직전에 준비(재고 행)를 넣는다 — 시험이 옵션을 다 넣은 뒤여야 해서. */
    List<Long> inStockProducts;

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
        tag = "t" + ShopFixtures.unique().replace("-", "");
        categoryId = fixtures.category();
        inStockProducts = new ArrayList<>();
    }

    // ── 노출 규칙 ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("노출 규칙")
    class Visibility {

        @Test
        @DisplayName("판매 방식별 준비(재고 행) · 공개 · 판매 중인 상품만 나온다 — 등록 기록만으로는 나오지 않는다")
        void onlyReadyVisibleActiveProducts() throws Exception {
            Long shown = visibleInStock("보임");
            Long noRegistration = fixtures.product(categoryId, "IN_STOCK", "ACTIVE", "등록 없음", tag);
            fixtures.option(noRegistration, "ACTIVE");
            Long incomplete = fixtures.product(categoryId, "IN_STOCK", "ACTIVE", "재고 행 전", tag);
            fixtures.option(incomplete, "ACTIVE");
            fixtures.registration(incomplete, ShopFixtures.unique());
            Long hidden = visibleInStock("비공개");
            jdbcTemplate.update("UPDATE products SET visible = 0 WHERE id = ?", hidden);
            Long paused = visibleInStock("판매 중지");
            jdbcTemplate.update("UPDATE products SET status = 'PAUSED' WHERE id = ?", paused);

            assertThat(ids(list())).containsExactly(shown).doesNotContain(noRegistration, incomplete, hidden, paused);
        }

        @Test
        @DisplayName("판매 중 옵션이 하나도 없는 상품(전부 판매 중지)은 목록에 남고 sellable=false 로 알린다 — 옵션이 아예 없는 일반 상품은 준비될 수 없어 나오지 않는다")
        void productsWithoutActiveOptionStayListedAsNotSellable() throws Exception {
            Long shown = visibleInStock("판매 중 옵션 있음");
            fixtures.option(shown, "ACTIVE", new BigDecimal("1000"));
            // 옵션이 없는 일반 상품 — 등록 API 로는 생기지 않는다(조합 0 은 400). 재고 행을 만들 옵션이 없어 준비될 수 없다
            Long noOptions = fixtures.product(categoryId, "IN_STOCK", "ACTIVE", "옵션 없음", tag);
            fixtures.registration(noOptions);
            Long allPaused = visibleInStock("전부 판매 중지");
            fixtures.option(allPaused, "PAUSED", new BigDecimal("1000"));
            Instant now = Instant.now();
            Long preorderAllPaused = visiblePreorder("사전예약 전부 판매 중지", now.minus(HOUR), now.plus(HOUR));
            fixtures.option(preorderAllPaused, "PAUSED", new BigDecimal("1000"));

            JsonNode items = list();
            assertThat(ids(items)).containsExactlyInAnyOrder(shown, allPaused, preorderAllPaused).doesNotContain(noOptions);
            assertThat(find(items, shown).get("sellable").asBoolean()).isTrue();
            assertThat(find(items, shown).get("minPrice").decimalValue()).isEqualByComparingTo("1000");
            for (Long notSellable : List.of(allPaused, preorderAllPaused)) {
                JsonNode item = find(items, notSellable);
                assertThat(item.get("sellable").asBoolean()).as("product %d", notSellable).isFalse();
                assertThat(item.get("minPrice").isNull()).isTrue();
                assertThat(item.get("status").asString()).as("상품 자체는 판매 중").isEqualTo("ACTIVE");
            }
            // 사전예약은 옵션이 전부 판매 중지여도 품절이 아니다(품절은 재고 개념) — 판매 중지 표시로만 알린다
            assertThat(find(items, preorderAllPaused).get("soldOut").asBoolean()).isFalse();
            assertThat(find(items, allPaused).get("soldOut").asBoolean()).as("일반은 살 수 있는 옵션이 없으니 품절").isTrue();
        }

        @Test
        @DisplayName("사전예약은 오픈 전 · 접수 중 · 마감 뒤 120시간 안이면 보이고 그 단계가 실린다. 120시간이 지나면 숨는다")
        void preorderPhasesAndHideAfterClose() throws Exception {
            Instant now = Instant.now();
            Long beforeOpen = visiblePreorder("오픈 전", now.plus(HOUR), now.plus(HOUR.multipliedBy(2)));
            Long open = visiblePreorder("접수 중", now.minus(HOUR), now.plus(HOUR));
            fixtures.option(open, "ACTIVE", new BigDecimal("1000"));
            Long closedRecently = visiblePreorder("마감 119h", now.minus(HOUR.multipliedBy(120)), now.minus(HOUR.multipliedBy(119)));
            Long closedLongAgo = visiblePreorder("마감 121h", now.minus(HOUR.multipliedBy(122)), now.minus(HOUR.multipliedBy(121)));
            Long noCampaign = fixtures.product(categoryId, "PREORDER", "ACTIVE", "회차 없음", tag);
            fixtures.registration(noCampaign);

            JsonNode items = list();
            assertThat(ids(items)).containsExactly(closedRecently, open, beforeOpen).doesNotContain(closedLongAgo, noCampaign);
            assertThat(find(items, beforeOpen).get("preorderStatus").asString()).isEqualTo("BEFORE_OPEN");
            assertThat(find(items, open).get("preorderStatus").asString()).isEqualTo("OPEN");
            assertThat(find(items, closedRecently).get("preorderStatus").asString()).isEqualTo("CLOSED");
            assertThat(find(items, open).get("opensAt").asString()).isNotBlank();
            assertThat(find(items, open).get("soldOut").asBoolean()).isFalse();
        }
    }

    // ── 가격 · 품절 · 사진 ──────────────────────────────────────────────────

    @Nested
    @DisplayName("가격 · 품절 · 대표 사진")
    class PriceStockImage {

        @Test
        @DisplayName("최저가는 판매 중 옵션 기준이다 — 품절 옵션은 포함, 판매 중지 옵션은 제외")
        void minPriceOverActiveOptions() throws Exception {
            Long productId = visibleInStock("가격");
            Long cheapPaused = fixtures.option(productId, "PAUSED", new BigDecimal("100"));
            Long soldOut = fixtures.option(productId, "ACTIVE", new BigDecimal("500"));
            fixtures.inventory(soldOut, 10, 4, 6);
            Long inStock = fixtures.option(productId, "ACTIVE", new BigDecimal("900"));
            fixtures.inventory(inStock, 1, 0, 0);

            JsonNode item = find(list(), productId);
            assertThat(item.get("minPrice").decimalValue()).isEqualByComparingTo("500");
            assertThat(item.get("soldOut").asBoolean()).isFalse();
            assertThat(item.get("status").asString()).isEqualTo("ACTIVE");
            assertThat(cheapPaused).isNotNull();
        }

        @Test
        @DisplayName("일반 상품은 가용 재고가 있는 판매 중 옵션이 없으면 품절(재고 행 없음 = 판매 불가). 사전예약은 품절이 없다")
        void soldOutWhenNoActiveOptionHasStock() throws Exception {
            Long allGone = visibleInStock("품절");
            Long gone = fixtures.option(allGone, "ACTIVE", new BigDecimal("1000"));
            fixtures.inventory(gone, 5, 2, 3);
            fixtures.option(allGone, "ACTIVE", new BigDecimal("1000"));            // 재고 행 없음
            Long pausedWithStock = fixtures.option(allGone, "PAUSED", new BigDecimal("1000"));
            fixtures.inventory(pausedWithStock, 9, 0, 0);                            // 판매 중지 옵션의 재고는 안 센다
            Instant now = Instant.now();
            Long preorderActive = visiblePreorder("사전예약 판매 중", now.minus(HOUR), now.plus(HOUR));
            fixtures.option(preorderActive, "ACTIVE", new BigDecimal("1000"));          // 재고 행 없어도 사전예약은 살 수 있다

            JsonNode items = list();
            assertThat(find(items, allGone).get("soldOut").asBoolean()).isTrue();
            assertThat(find(items, allGone).get("minPrice").decimalValue()).isEqualByComparingTo("1000");
            // 사전예약은 재고 행이 없으므로 품절이 되지 않는다
            assertThat(find(items, preorderActive).get("soldOut").asBoolean()).isFalse();
        }

        @Test
        @DisplayName("대표 사진은 기본 묶음의 대표를 먼저, 없으면 첫 색상 묶음의 대표를, 사진이 없으면 null")
        void representativeImage() throws Exception {
            // 대표가 늘 첫 칸이면 "대표" 와 "첫 칸" 이 갈리지 않는다 — 비대표를 먼저(작은 id · position 0), 대표를 position 1 에
            Long withDefault = visibleInStock("기본 묶음");
            fixtures.image(withDefault, "GALLERY", "화이트", 0, true, "https://img/white-primary.jpg");
            fixtures.image(withDefault, "GALLERY", "", 0, false, "https://img/default-first.jpg");
            fixtures.image(withDefault, "GALLERY", "", 1, true, "https://img/default-primary.jpg");
            Long colorOnly = visibleInStock("색상 묶음만");
            fixtures.image(colorOnly, "GALLERY", "화이트", 0, true, "https://img/white.jpg");
            fixtures.image(colorOnly, "GALLERY", "블랙", 0, false, "https://img/black-first.jpg");
            fixtures.image(colorOnly, "GALLERY", "블랙", 1, true, "https://img/black-primary.jpg");
            fixtures.image(colorOnly, "DETAIL", "spec", 0, true, "https://img/detail.jpg");
            Long none = visibleInStock("사진 없음");

            JsonNode items = list();
            assertThat(find(items, withDefault).get("imageUrl").asString()).isEqualTo("https://img/default-primary.jpg");
            assertThat(find(items, colorOnly).get("imageUrl").asString()).isEqualTo("https://img/black-primary.jpg");
            assertThat(find(items, none).get("imageUrl").isNull()).isTrue();
        }
    }

    // ── 필터 ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("필터")
    class Filters {

        @Test
        @DisplayName("q 는 상품명 · tags 부분 일치이고 대소문자를 가리지 않으며 와일드카드는 문자다")
        void keywordSearch() throws Exception {
            // q 를 바꾸면 tag 로 못 좁히므로 제목 · tags 에 tag 를 넣어 자기 상품만 잡히게 한다
            Long byTitle = visibleInStock("Galaxy Fold " + tag);
            Long byTags = fixtures.product(categoryId, "IN_STOCK", "ACTIVE", "다른 이름", "fold " + tag);
            fixtures.registration(byTags);
            fixtures.option(byTags, "PAUSED");
            inStockProducts.add(byTags);
            Long underscore = visibleInStock("a_b " + tag);
            Long axb = visibleInStock("axb " + tag);                 // _ 가 와일드카드면 여기도 걸린다
            Long backslash = visibleInStock("a\\b " + tag);          // 제목에 \ 한 글자
            Long other = visibleInStock("무관 " + tag);

            assertThat(ids(list("q", "FOLD " + tag.toUpperCase()))).containsExactlyInAnyOrder(byTitle, byTags).doesNotContain(other);
            assertThat(ids(list("q", "a_b " + tag))).containsExactly(underscore).doesNotContain(axb);
            assertThat(ids(list("q", "a%b " + tag))).isEmpty();
            assertThat(ids(list("q", "a\\b " + tag))).containsExactly(backslash);
            // q 도 NFC · 트림 · 공백 접기 — 콜레이션은 LIKE 에서 NFD 를 같게 보지 않는다
            assertThat(ids(list("q", "  galaxy   fold " + tag + "  "))).containsExactly(byTitle);
            assertThat(ids(list("q", Normalizer.normalize("갤럭시", Normalizer.Form.NFD) + " " + tag))).isEmpty();
            Long hangul = visibleInStock("갤럭시 " + tag);
            assertThat(ids(list("q", Normalizer.normalize("갤럭시 ", Normalizer.Form.NFD) + tag))).containsExactly(hangul);
        }

        @Test
        @DisplayName("saleMode 와 categoryId 로 좁힌다 — 상위 카테고리는 하위에 배정된 상품까지 포함한다")
        void saleModeAndCategory() throws Exception {
            Long child = fixtures.childCategory(categoryId, "삼성");
            Long otherRoot = fixtures.category();
            Long inParent = visibleInStock("상위 직접");
            Long inChild = fixtures.product(child, "IN_STOCK", "ACTIVE", "하위", tag);
            fixtures.registration(inChild);
            fixtures.option(inChild, "PAUSED");
            inStockProducts.add(inChild);
            Long elsewhere = fixtures.product(otherRoot, "IN_STOCK", "ACTIVE", "다른 상위", tag);
            fixtures.registration(elsewhere);
            fixtures.option(elsewhere, "PAUSED");
            inStockProducts.add(elsewhere);
            Instant now = Instant.now();
            Long preorder = visiblePreorder("사전예약", now.minus(HOUR), now.plus(HOUR));

            assertThat(ids(list("categoryId", String.valueOf(categoryId)))).containsExactlyInAnyOrder(inParent, inChild, preorder);
            assertThat(ids(list("categoryId", String.valueOf(child)))).containsExactly(inChild);
            assertThat(ids(list("saleMode", "PREORDER"))).containsExactly(preorder);
            assertThat(ids(list("saleMode", "IN_STOCK"))).containsExactlyInAnyOrder(inParent, inChild, elsewhere);
        }

        @Test
        @DisplayName("색상 · 용량은 축 안에서 OR, 축 사이는 AND 이고 같은 판매 중 옵션이 함께 만족해야 한다")
        void colorAndStorageMatchSameOption() throws Exception {
            Long productId = visibleInStock("옵션 조합");
            Long color = fixtures.axis(productId, "color", 0);
            Long storage = fixtures.axis(productId, "storage", 1);
            Long black = fixtures.value(color, "블랙", 0);
            Long white = fixtures.value(color, "화이트", 1);
            Long gb256 = fixtures.value(storage, "256 GB", "256GB", 0);   // 표시값과 정규화값을 갈라 어느 칸으로 비교하는지 가른다
            Long gb512 = fixtures.value(storage, "512 GB", "512GB", 1);
            // 판매 중: 블랙 256 · 화이트 512. 판매 중지: 화이트 256 — 축마다 따로 만족하면 안 되고 같은 옵션이어야 한다
            Long black256 = fixtures.option(productId, "ACTIVE", new BigDecimal("1000"));
            fixtures.selection(productId, black256, color, black);
            fixtures.selection(productId, black256, storage, gb256);
            Long white512 = fixtures.option(productId, "ACTIVE", new BigDecimal("1000"));
            fixtures.selection(productId, white512, color, white);
            fixtures.selection(productId, white512, storage, gb512);
            Long white256Paused = fixtures.option(productId, "PAUSED", new BigDecimal("1000"));
            fixtures.selection(productId, white256Paused, color, white);
            fixtures.selection(productId, white256Paused, storage, gb256);

            assertThat(ids(list("color", "블랙"))).containsExactly(productId);
            assertThat(ids(list("color", "화이트"))).containsExactly(productId);            // 화이트 512 가 판매 중
            assertThat(ids(list("color", "블랙", "storage", "256GB"))).containsExactly(productId);
            assertThat(ids(list("color", "화이트", "storage", "512GB"))).containsExactly(productId);
            assertThat(ids(list("color", "화이트", "storage", "256GB"))).isEmpty();       // 화이트도 있고 256 도 있지만 같은 판매 중 옵션이 아니다(화이트 256 은 판매 중지)
            assertThat(ids(list("color", "블랙", "storage", "512GB"))).isEmpty();         // 같은 옵션이 아니다 — 둘 다 판매 중인데도
            assertThat(ids(list("color", "블랙", "color", "화이트", "storage", "256GB"))).containsExactly(productId);
            // 필터 값은 저장과 같은 정규화 — 용량은 공백 제거 · 대문자, 색상은 트림 · NFC
            assertThat(ids(list("storage", "256 gb"))).containsExactly(productId);
            assertThat(ids(list("color", "  블랙  "))).containsExactly(productId);
            assertThat(ids(list("color", Normalizer.normalize("블랙", Normalizer.Form.NFD)))).containsExactly(productId);
            assertThat(ids(list("color", "블랙 ", "storage", "256 GB"))).containsExactly(productId);
        }
    }

    // ── 페이징 · 검증 ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("페이징")
    class Paging {

        @Test
        @DisplayName("productId 내림차순 고정, page · size · total · hasNext")
        void orderedAndPaged() throws Exception {
            Long first = visibleInStock("1");
            Long second = visibleInStock("2");
            Long third = visibleInStock("3");

            JsonNode page0 = data(perform("size", "2"));
            assertThat(page0.get("page").asInt()).isEqualTo(0);
            assertThat(page0.get("size").asInt()).isEqualTo(2);
            assertThat(page0.get("total").asLong()).isEqualTo(3);
            assertThat(page0.get("hasNext").asBoolean()).isTrue();
            assertThat(ids(page0.get("items"))).containsExactly(third, second);

            JsonNode page1 = data(perform("size", "2", "page", "1"));
            assertThat(page1.get("hasNext").asBoolean()).isFalse();
            assertThat(ids(page1.get("items"))).containsExactly(first);
        }

        @Test
        @DisplayName("size 를 안 주면 20 이다")
        void defaultSizeIsTwenty() throws Exception {
            Long productId = visibleInStock("기본 크기");
            fixtures.stockReady(productId);   // size 를 안 주려고 perform() 을 거치지 않으니 준비를 직접 넣는다
            JsonNode page = JSON.readTree(mockMvc.perform(get("/api/v1/products").param("q", tag))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("data");
            assertThat(page.get("size").asInt()).isEqualTo(20);
            assertThat(page.get("page").asInt()).isEqualTo(0);
            assertThat(ids(page.get("items"))).as("빈 목록이 아니라 실제로 상품이 실린 첫 쪽").containsExactly(productId);
        }

        @Test
        @DisplayName("size 는 1~100, page 는 0 이상 — 벗어나면 400 VALIDATION_FAILED")
        void validatesPageAndSize() throws Exception {
            for (String[] bad : new String[][] {{"size", "0"}, {"size", "101"}, {"page", "-1"}}) {
                perform(bad)
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                        .andExpect(jsonPath("$.error.details.violations[0].field").value(bad[0]));
            }
            perform(new String[] {"saleMode", "RENTAL"}).andExpect(status().isBadRequest());
        }
    }

    // ── 도우미 ──────────────────────────────────────────────────────────────

    /**
     * 등록된 일반 상품. 실제 등록은 조합이 하나 이상이라 판매 중지 옵션 하나로 시작한다 — 판매 중지 옵션은 최저가 · 판매 가능 · 품절 · 필터에
     * 들어가지 않아 시험이 넣는 옵션의 판정을 바꾸지 않는다. 재고 행은 목록을 부르기 직전에 넣는다(시험이 재고를 직접 넣었으면 그대로).
     */
    private Long visibleInStock(String title) {
        Long productId = fixtures.product(categoryId, "IN_STOCK", "ACTIVE", title, tag);
        fixtures.registration(productId);
        fixtures.option(productId, "PAUSED");
        inStockProducts.add(productId);
        return productId;
    }

    private Long visiblePreorder(String title, Instant opensAt, Instant closesAt) {
        Long productId = fixtures.product(categoryId, "PREORDER", "ACTIVE", title, tag);
        fixtures.registration(productId);
        fixtures.campaign(productId, opensAt, closesAt);
        return productId;
    }

    /** 이 시험의 tags 로 좁힌 목록. 추가 파라미터는 이름 · 값 쌍이다. */
    private JsonNode list(String... params) throws Exception {
        return data(perform(params)).get("items");
    }

    /** 기본은 q=tag · size=100. 같은 이름을 넘기면 기본을 덮어쓴다(색상처럼 반복된 이름은 전부 싣는다). */
    private ResultActions perform(String... params) throws Exception {
        inStockProducts.forEach(fixtures::stockReady);
        LinkedMultiValueMap<String, String> query = new LinkedMultiValueMap<>();
        for (int i = 0; i < params.length; i += 2) {
            query.add(params[i], params[i + 1]);
        }
        query.putIfAbsent("q", List.of(tag));
        query.putIfAbsent("size", List.of("100"));
        MockHttpServletRequestBuilder request = get("/api/v1/products");
        query.forEach((name, values) -> request.param(name, values.toArray(String[]::new)));
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
