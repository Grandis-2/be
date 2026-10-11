package com.grandis.nova.catalog.api;

import com.grandis.nova.catalog.support.CatalogIntegrationTest;
import com.grandis.nova.catalog.support.ShopFixtures;
import com.grandis.nova.common.UuidBinary;
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
import java.util.UUID;

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
    UUID categoryId;
    /** visibleInStock 으로 만든 일반 상품. 목록을 부르기 직전에 준비(재고 행)를 넣는다 — 시험이 옵션을 다 넣은 뒤여야 해서. */
    List<UUID> inStockProducts;

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
            UUID shown = visibleInStock("보임");
            UUID noRegistration = fixtures.product(categoryId, "IN_STOCK", "ACTIVE", "등록 없음", tag);
            fixtures.option(noRegistration, "ACTIVE");
            UUID incomplete = fixtures.product(categoryId, "IN_STOCK", "ACTIVE", "재고 행 전", tag);
            fixtures.option(incomplete, "ACTIVE");
            fixtures.registration(incomplete, ShopFixtures.unique());
            UUID hidden = visibleInStock("비공개");
            jdbcTemplate.update("UPDATE products SET visible = 0 WHERE id = ?", UuidBinary.toBytes(hidden));
            UUID paused = visibleInStock("판매 중지");
            jdbcTemplate.update("UPDATE products SET status = 'PAUSED' WHERE id = ?", UuidBinary.toBytes(paused));

            assertThat(ids(list())).containsExactly(shown).doesNotContain(noRegistration, incomplete, hidden, paused);
        }

        @Test
        @DisplayName("판매 중 옵션이 하나도 없는 상품(전부 판매 중지)은 목록에 남고 sellable=false 로 알린다 — 옵션이 아예 없는 일반 상품은 준비될 수 없어 나오지 않는다")
        void productsWithoutActiveOptionStayListedAsNotSellable() throws Exception {
            UUID shown = visibleInStock("판매 중 옵션 있음");
            fixtures.option(shown, "ACTIVE", new BigDecimal("1000"));
            // 옵션이 없는 일반 상품 — 등록 API 로는 생기지 않는다(조합 0 은 400). 재고 행을 만들 옵션이 없어 준비될 수 없다
            UUID noOptions = fixtures.product(categoryId, "IN_STOCK", "ACTIVE", "옵션 없음", tag);
            fixtures.registration(noOptions);
            UUID allPaused = visibleInStock("전부 판매 중지");
            fixtures.option(allPaused, "PAUSED", new BigDecimal("1000"));
            Instant now = Instant.now();
            UUID preorderAllPaused = visiblePreorder("사전예약 전부 판매 중지", now.minus(HOUR), now.plus(HOUR));
            fixtures.option(preorderAllPaused, "PAUSED", new BigDecimal("1000"));

            JsonNode items = list();
            assertThat(ids(items)).containsExactlyInAnyOrder(shown, allPaused, preorderAllPaused).doesNotContain(noOptions);
            assertThat(find(items, shown).get("sellable").asBoolean()).isTrue();
            assertThat(find(items, shown).get("minPrice").decimalValue()).isEqualByComparingTo("1000");
            for (UUID notSellable : List.of(allPaused, preorderAllPaused)) {
                JsonNode item = find(items, notSellable);
                assertThat(item.get("sellable").asBoolean()).as("product %s", notSellable).isFalse();
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
            UUID beforeOpen = visiblePreorder("오픈 전", now.plus(HOUR), now.plus(HOUR.multipliedBy(2)));
            UUID open = visiblePreorder("접수 중", now.minus(HOUR), now.plus(HOUR));
            fixtures.option(open, "ACTIVE", new BigDecimal("1000"));
            UUID closedRecently = visiblePreorder("마감 119h", now.minus(HOUR.multipliedBy(120)), now.minus(HOUR.multipliedBy(119)));
            UUID closedLongAgo = visiblePreorder("마감 121h", now.minus(HOUR.multipliedBy(122)), now.minus(HOUR.multipliedBy(121)));
            UUID noCampaign = fixtures.product(categoryId, "PREORDER", "ACTIVE", "회차 없음", tag);
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
            UUID productId = visibleInStock("가격");
            UUID cheapPaused = fixtures.option(productId, "PAUSED", new BigDecimal("100"));
            UUID soldOut = fixtures.option(productId, "ACTIVE", new BigDecimal("500"));
            fixtures.inventory(soldOut, 10, 4, 6);
            UUID inStock = fixtures.option(productId, "ACTIVE", new BigDecimal("900"));
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
            UUID allGone = visibleInStock("품절");
            UUID gone = fixtures.option(allGone, "ACTIVE", new BigDecimal("1000"));
            fixtures.inventory(gone, 5, 2, 3);
            fixtures.option(allGone, "ACTIVE", new BigDecimal("1000"));            // 재고 행 없음
            UUID pausedWithStock = fixtures.option(allGone, "PAUSED", new BigDecimal("1000"));
            fixtures.inventory(pausedWithStock, 9, 0, 0);                            // 판매 중지 옵션의 재고는 안 센다
            Instant now = Instant.now();
            UUID preorderActive = visiblePreorder("사전예약 판매 중", now.minus(HOUR), now.plus(HOUR));
            fixtures.option(preorderActive, "ACTIVE", new BigDecimal("1000"));          // 재고 행 없어도 사전예약은 살 수 있다

            JsonNode items = list();
            assertThat(find(items, allGone).get("soldOut").asBoolean()).isTrue();
            assertThat(find(items, allGone).get("minPrice").decimalValue()).isEqualByComparingTo("1000");
            // 사전예약은 재고 행이 없으므로 품절이 되지 않는다
            assertThat(find(items, preorderActive).get("soldOut").asBoolean()).isFalse();
        }

        @Test
        @DisplayName("썸네일은 첫 색상(넣은 순서)의 첫 장, 색상 축이 없으면 기본 묶음의 첫 장 — 대표 표시는 안 보고, 첫 색상에 사진이 없으면 null")
        void representativeImage() throws Exception {
            // 대표가 늘 첫 칸이면 "대표" 와 "첫 칸" 이 갈리지 않는다 — 비대표를 position 0 에, 대표를 position 1 에
            UUID withDefault = visibleInStock("기본 묶음");
            fixtures.image(withDefault, "GALLERY", "", 0, false, "https://img/default-first.jpg");
            fixtures.image(withDefault, "GALLERY", "", 1, true, "https://img/default-primary.jpg");
            // 화이트를 먼저 넣고 사전순으로 앞서는 블루를 다음에 — 사전순이면 블루가 이긴다
            UUID colorOnly = visibleInStock("색상 묶음만");
            fixtures.image(colorOnly, "GALLERY", "화이트", 0, false, "https://img/white-first.jpg");
            fixtures.image(colorOnly, "GALLERY", "화이트", 1, true, "https://img/white-primary.jpg");
            fixtures.image(colorOnly, "GALLERY", "블루", 0, true, "https://img/blue.jpg");
            fixtures.image(colorOnly, "DETAIL", "spec", 0, true, "https://img/detail.jpg");
            // 첫 색상(블랙)에 사진이 없으면 다음 색상(화이트)으로 넘어가지 않는다
            UUID firstColorBare = visibleInStock("첫 색상 사진 없음");
            fixtures.value(fixtures.axis(firstColorBare, "color", 0), "블랙", 0);
            fixtures.image(firstColorBare, "GALLERY", "화이트", 0, true, "https://img/later-white.jpg");
            UUID none = visibleInStock("사진 없음");

            JsonNode items = list();
            assertThat(find(items, withDefault).get("imageUrl").asString()).isEqualTo("https://img/default-first.jpg");
            assertThat(find(items, colorOnly).get("imageUrl").asString()).isEqualTo("https://img/white-first.jpg");
            assertThat(find(items, firstColorBare).get("imageUrl").isNull()).isTrue();
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
            UUID byTitle = visibleInStock("Galaxy Fold " + tag);
            UUID byTags = fixtures.product(categoryId, "IN_STOCK", "ACTIVE", "다른 이름", "fold " + tag);
            fixtures.registration(byTags);
            fixtures.option(byTags, "PAUSED");
            inStockProducts.add(byTags);
            UUID underscore = visibleInStock("a_b " + tag);
            UUID axb = visibleInStock("axb " + tag);                 // _ 가 와일드카드면 여기도 걸린다
            UUID backslash = visibleInStock("a\\b " + tag);          // 제목에 \ 한 글자
            UUID other = visibleInStock("무관 " + tag);

            assertThat(ids(list("q", "FOLD " + tag.toUpperCase()))).containsExactlyInAnyOrder(byTitle, byTags).doesNotContain(other);
            assertThat(ids(list("q", "a_b " + tag))).containsExactly(underscore).doesNotContain(axb);
            assertThat(ids(list("q", "a%b " + tag))).isEmpty();
            assertThat(ids(list("q", "a\\b " + tag))).containsExactly(backslash);
            // q 도 NFC · 트림 · 공백 접기 — 콜레이션은 LIKE 에서 NFD 를 같게 보지 않는다
            assertThat(ids(list("q", "  galaxy   fold " + tag + "  "))).containsExactly(byTitle);
            assertThat(ids(list("q", Normalizer.normalize("갤럭시", Normalizer.Form.NFD) + " " + tag))).isEmpty();
            UUID hangul = visibleInStock("갤럭시 " + tag);
            assertThat(ids(list("q", Normalizer.normalize("갤럭시 ", Normalizer.Form.NFD) + tag))).containsExactly(hangul);
        }

        @Test
        @DisplayName("saleMode 와 categoryId 로 좁힌다 — 상위 카테고리는 하위에 배정된 상품까지 포함한다")
        void saleModeAndCategory() throws Exception {
            UUID child = fixtures.childCategory(categoryId, "삼성");
            UUID otherRoot = fixtures.category();
            UUID inParent = visibleInStock("상위 직접");
            UUID inChild = fixtures.product(child, "IN_STOCK", "ACTIVE", "하위", tag);
            fixtures.registration(inChild);
            fixtures.option(inChild, "PAUSED");
            inStockProducts.add(inChild);
            UUID elsewhere = fixtures.product(otherRoot, "IN_STOCK", "ACTIVE", "다른 상위", tag);
            fixtures.registration(elsewhere);
            fixtures.option(elsewhere, "PAUSED");
            inStockProducts.add(elsewhere);
            Instant now = Instant.now();
            UUID preorder = visiblePreorder("사전예약", now.minus(HOUR), now.plus(HOUR));

            assertThat(ids(list("categoryId", String.valueOf(categoryId)))).containsExactlyInAnyOrder(inParent, inChild, preorder);
            assertThat(ids(list("categoryId", String.valueOf(child)))).containsExactly(inChild);
            assertThat(ids(list("saleMode", "PREORDER"))).containsExactly(preorder);
            assertThat(ids(list("saleMode", "IN_STOCK"))).containsExactlyInAnyOrder(inParent, inChild, elsewhere);
        }

        @Test
        @DisplayName("색상 · 용량은 축 안에서 OR, 축 사이는 AND 이고 같은 판매 중 옵션이 함께 만족해야 한다")
        void colorAndStorageMatchSameOption() throws Exception {
            UUID productId = visibleInStock("옵션 조합");
            ShopFixtures.AxisRef color = fixtures.axis(productId, "color", 0);
            ShopFixtures.AxisRef storage = fixtures.axis(productId, "storage", 1);
            String black = fixtures.value(color, "블랙", 0);
            String white = fixtures.value(color, "화이트", 1);
            String gb256 = fixtures.value(storage, "256 GB", "256GB", 0);   // 표시값과 정규화값을 갈라 어느 칸으로 비교하는지 가른다
            String gb512 = fixtures.value(storage, "512 GB", "512GB", 1);
            // 판매 중: 블랙 256 · 화이트 512. 판매 중지: 화이트 256 — 축마다 따로 만족하면 안 되고 같은 옵션이어야 한다
            UUID black256 = fixtures.option(productId, "ACTIVE", new BigDecimal("1000"));
            fixtures.selection(productId, black256, color, black);
            fixtures.selection(productId, black256, storage, gb256);
            UUID white512 = fixtures.option(productId, "ACTIVE", new BigDecimal("1000"));
            fixtures.selection(productId, white512, color, white);
            fixtures.selection(productId, white512, storage, gb512);
            UUID white256Paused = fixtures.option(productId, "PAUSED", new BigDecimal("1000"));
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

        @Test
        @DisplayName("색상 필터는 대소문자 · 악센트를 가리지 않는다 — 같은 축의 중복 판정과 같은 콜레이션이라 Space Gray 로 넣고 space gray 로 찾는다")
        void colorFilterFoldsCaseAndAccents() throws Exception {
            UUID productId = visibleInStock("콜레이션");
            ShopFixtures.AxisRef color = fixtures.axis(productId, "color", 0);
            String gray = fixtures.value(color, "Space Gray", 0);
            String rose = fixtures.value(color, "Rosé", 1);
            UUID grayOption = fixtures.option(productId, "ACTIVE", new BigDecimal("1000"));
            fixtures.selection(productId, grayOption, color, gray);
            UUID roseOption = fixtures.option(productId, "ACTIVE", new BigDecimal("1000"));
            fixtures.selection(productId, roseOption, color, rose);

            assertThat(ids(list("color", "space gray"))).containsExactly(productId);
            assertThat(ids(list("color", "SPACE GRAY"))).containsExactly(productId);
            assertThat(ids(list("color", "Rose"))).containsExactly(productId);
            assertThat(ids(list("color", "Space Grey"))).as("철자가 다르면 안 걸린다").isEmpty();
        }
    }

    // ── 페이징 · 검증 ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("페이징")
    class Paging {

        @Test
        @DisplayName("기본(최신순)은 등록 시각 내림차순, page · size · total · hasNext")
        void orderedAndPaged() throws Exception {
            UUID first = visibleInStock("1");
            UUID second = visibleInStock("2");
            UUID third = visibleInStock("3");

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
            UUID productId = visibleInStock("기본 크기");
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

    @Nested
    @DisplayName("정렬 — 최신순(기본) · 낮은 가격순 · 높은 가격순")
    class Sorting {

        @Test
        @DisplayName("가격순은 판매 중 옵션의 최저가로, 같으면 최신순, 판매 중 옵션이 없는 상품은 두 가격순 모두 맨 뒤다")
        void sortsByMinPriceThenNewest() throws Exception {
            // 생성 순 A · B · C · D. 최저가 A 3000(판매 중지 100 은 빼고) · B 1000(최고 5000) · C 1000 · D 없음(판매 중 옵션 없음)
            UUID a = visibleInStock("A");
            fixtures.option(a, "PAUSED", new BigDecimal("100"));
            fixtures.option(a, "ACTIVE", new BigDecimal("3000"));
            UUID b = visibleInStock("B");
            fixtures.option(b, "ACTIVE", new BigDecimal("1000"));
            fixtures.option(b, "ACTIVE", new BigDecimal("5000"));
            UUID c = visibleInStock("C");
            fixtures.option(c, "ACTIVE", new BigDecimal("1000"));
            UUID d = visibleInStock("D");

            assertThat(ids(list())).as("기본은 최신순").containsExactly(d, c, b, a);
            assertThat(ids(list("sort", "NEWEST"))).containsExactly(d, c, b, a);
            // 같은 1000 은 최신(C)이 먼저 — 동률을 생성 순으로 잇거나 최저가가 없는 D 를 앞에 두면 다르게 나온다
            assertThat(ids(list("sort", "PRICE_ASC"))).containsExactly(c, b, a, d);
            // 최고가로 세우면 B(5000)가 맨 앞이다 — 카드에 보이는 최저가 기준이라 A(3000)가 먼저
            assertThat(ids(list("sort", "PRICE_DESC"))).containsExactly(a, c, b, d);
        }

        @Test
        @DisplayName("정렬한 채로 쪽을 나눈다 — 건수는 정렬과 무관")
        void pagesInSortedOrder() throws Exception {
            UUID cheap = visibleInStock("싼");
            fixtures.option(cheap, "ACTIVE", new BigDecimal("1000"));
            UUID middle = visibleInStock("중간");
            fixtures.option(middle, "ACTIVE", new BigDecimal("2000"));
            UUID expensive = visibleInStock("비싼");
            fixtures.option(expensive, "ACTIVE", new BigDecimal("3000"));

            JsonNode page0 = data(perform("sort", "PRICE_ASC", "size", "2"));
            assertThat(page0.get("total").asLong()).isEqualTo(3);
            assertThat(ids(page0.get("items"))).containsExactly(cheap, middle);
            assertThat(ids(data(perform("sort", "PRICE_ASC", "size", "2", "page", "1")).get("items"))).containsExactly(expensive);
        }

        @Test
        @DisplayName("셋이 아닌 정렬 값(추천순 · 별점순 · 소문자)은 400 VALIDATION_FAILED, 칸은 sort")
        void rejectsOtherSorts() throws Exception {
            for (String bad : new String[] {"RECOMMENDED", "RATING_DESC", "price_asc"}) {
                perform("sort", bad)
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                        .andExpect(jsonPath("$.error.details.violations[0].field").value("sort"));
            }
        }
    }

    // ── 도우미 ──────────────────────────────────────────────────────────────

    /**
     * 등록된 일반 상품. 실제 등록은 조합이 하나 이상이라 판매 중지 옵션 하나로 시작한다 — 판매 중지 옵션은 최저가 · 판매 가능 · 품절 · 필터에
     * 들어가지 않아 시험이 넣는 옵션의 판정을 바꾸지 않는다. 재고 행은 목록을 부르기 직전에 넣는다(시험이 재고를 직접 넣었으면 그대로).
     */
    private UUID visibleInStock(String title) {
        UUID productId = fixtures.product(categoryId, "IN_STOCK", "ACTIVE", title, tag);
        fixtures.registration(productId);
        fixtures.option(productId, "PAUSED");
        inStockProducts.add(productId);
        return productId;
    }

    private UUID visiblePreorder(String title, Instant opensAt, Instant closesAt) {
        UUID productId = fixtures.product(categoryId, "PREORDER", "ACTIVE", title, tag);
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

    private static List<UUID> ids(JsonNode items) {
        List<UUID> ids = new ArrayList<>();
        for (JsonNode item : items) {
            ids.add(UUID.fromString(item.get("productId").asString()));
        }
        return ids;
    }

    private static JsonNode find(JsonNode items, UUID productId) {
        for (JsonNode item : items) {
            if (item.get("productId").asString().equals(productId.toString())) {
                return item;
            }
        }
        throw new AssertionError("product " + productId + " not in " + items);
    }
}
