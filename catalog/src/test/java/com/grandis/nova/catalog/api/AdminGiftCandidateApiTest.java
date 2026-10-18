package com.grandis.nova.catalog.api;

import com.grandis.nova.catalog.support.CatalogIntegrationTest;
import com.grandis.nova.catalog.support.ShopFixtures;
import com.grandis.nova.common.UuidBinary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 럭키 드로우 증정품 후보 — 실제 MySQL. 같은 컨테이너의 다른 시험 상품이 섞이지 않게 시험마다 고유한 tags 로 q 를 건다.
 */
@CatalogIntegrationTest
@AutoConfigureMockMvc
@DisplayName("관리자 · 럭키 드로우 증정품 후보")
class AdminGiftCandidateApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PATH = "/api/v1/admin/products/gift-candidates";

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;

    ShopFixtures fixtures;
    String tag;
    UUID categoryId;

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
        tag = "g" + ShopFixtures.unique().replace("-", "");
        categoryId = fixtures.category();
    }

    @Test
    @DisplayName("일반 판매 · 판매 중 · 판매 준비이고 판매 중 옵션이 있는 상품만, 재고 행이 있는 판매 중 옵션과 확보할 수 있는 수량을(order 재고) — 비공개 상품도 나온다. 판매 중 옵션에 재고 행이 아직 없으면 옵션 없이 나오고, 재고 행만 있고 판매 준비 전이면 빠진다")
    void listsGiftableProductsWithTheirOptions() throws Exception {
        UUID shown = product("IN_STOCK", "ACTIVE", "증정 상품");
        UUID giftable = fixtures.option(shown, "ACTIVE");
        fixtures.inventory(giftable, 10, 3, 2);
        UUID paused = fixtures.option(shown, "PAUSED");
        fixtures.inventory(paused, 5, 0, 0);
        fixtures.option(shown, "ACTIVE");                              // 재고 행 없음 — 후보 아님
        UUID hidden = product("IN_STOCK", "ACTIVE", "비공개 증정 상품");
        jdbcTemplate.update("UPDATE products SET visible = 0 WHERE id = ?", (Object) UuidBinary.toBytes(hidden));
        UUID hiddenOption = fixtures.option(hidden, "ACTIVE");
        fixtures.inventory(hiddenOption, 1, 1, 0);                     // 다 확보됨 — 후보로는 나오고 available 0

        UUID preorder = product("PREORDER", "ACTIVE", "사전예약");
        fixtures.option(preorder, "ACTIVE");
        UUID pausedProduct = product("IN_STOCK", "PAUSED", "판매 중지");
        fixtures.inventory(fixtures.option(pausedProduct, "ACTIVE"), 5, 0, 0);
        UUID noStock = product("IN_STOCK", "ACTIVE", "재고 미등록");
        fixtures.option(noStock, "ACTIVE");
        UUID notReady = product("IN_STOCK", "ACTIVE", "판매 준비 전");               // 재고 행은 있지만 판매 준비 이벤트 전 — 회차를 못 만드니 후보 아님
        fixtures.inventoryOnly(fixtures.option(notReady, "ACTIVE"), 5, 0, 0);
        UUID onlyPausedStocked = product("IN_STOCK", "ACTIVE", "재고 옵션이 판매 중지");
        fixtures.inventory(fixtures.option(onlyPausedStocked, "PAUSED"), 5, 0, 0);
        fixtures.option(onlyPausedStocked, "ACTIVE");

        JsonNode data = list(tag);

        assertThat(ids(data.get("items"))).containsExactlyInAnyOrder(shown, hidden, onlyPausedStocked);
        assertThat(data.get("total").asLong()).isEqualTo(3);
        assertThat(find(data.get("items"), onlyPausedStocked).get("options")).as("판매 중 옵션의 재고 행이 아직 없다 — 고를 옵션 없음").isEmpty();
        JsonNode shownItem = find(data.get("items"), shown);
        assertThat(shownItem.get("visible").asBoolean()).isTrue();
        assertThat(shownItem.get("title").asString()).isEqualTo("증정 상품");
        assertThat(shownItem.get("options")).hasSize(1);
        JsonNode option = shownItem.get("options").get(0);
        assertThat(option.get("optionId").asString()).isEqualTo(giftable.toString());
        assertThat(option.get("title").asString()).isEqualTo("블랙 / 256GB");
        assertThat(option.get("available").asInt()).isEqualTo(5);
        JsonNode hiddenItem = find(data.get("items"), hidden);
        assertThat(hiddenItem.get("visible").asBoolean()).isFalse();
        assertThat(hiddenItem.get("options").get(0).get("available").asInt()).isZero();
    }

    @Test
    @DisplayName("q 는 상품명으로도 거르고 · 쪽을 나눈다 — 두 쪽이 겹치지 않고 최신순, 범위 밖 쪽은 비고 total 은 그대로, 기본값은 첫 쪽")
    void filtersByTitleAndPages() throws Exception {
        UUID first = product("IN_STOCK", "ACTIVE", "갤럭시 " + tag);
        fixtures.inventory(fixtures.option(first, "ACTIVE"), 3, 0, 0);
        UUID second = product("IN_STOCK", "ACTIVE", "아이폰 " + tag);
        fixtures.inventory(fixtures.option(second, "ACTIVE"), 3, 0, 0);

        assertThat(ids(list("갤럭시 " + tag).get("items"))).containsExactly(first);
        JsonNode firstPage = page("0", "1");
        JsonNode secondPage = page("1", "1");
        JsonNode beyond = page("2", "1");
        assertThat(firstPage.get("total").asLong()).isEqualTo(2);
        assertThat(firstPage.get("hasNext").asBoolean()).isTrue();
        assertThat(secondPage.get("total").asLong()).isEqualTo(2);
        assertThat(secondPage.get("hasNext").asBoolean()).isFalse();
        List<UUID> both = new ArrayList<>(ids(firstPage.get("items")));
        both.addAll(ids(secondPage.get("items")));
        assertThat(both).as("두 쪽이 겹치지 않고 최신순").containsExactly(second, first);
        assertThat(beyond.get("items")).as("범위 밖 쪽").isEmpty();
        assertThat(beyond.get("total").asLong()).isEqualTo(2);
        JsonNode defaults = JSON.readTree(mockMvc.perform(get(PATH).queryParam("q", tag).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("data");
        assertThat(defaults.get("page").asInt()).as("기본 page").isZero();
        assertThat(ids(defaults.get("items"))).containsExactly(second, first);
    }

    private JsonNode page(String page, String size) throws Exception {
        return JSON.readTree(mockMvc.perform(get(PATH).queryParam("q", tag).queryParam("page", page).queryParam("size", size)
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("data");
    }

    @Test
    @DisplayName("q 는 정규화한 뒤 50자까지, 쪽은 앞 10,000개 안에서 시작 — 넘으면 그 칸의 400 VALIDATION_FAILED")
    void sharesListLimits() throws Exception {
        String fifty = tag + " " + "가".repeat(50 - tag.length() - 1);
        list(fifty);
        page("99", "100");
        for (String[] bad : new String[][] {{"q", fifty + "가"}, {"page", "100"}}) {
            var request = get(PATH).queryParam("size", "100").with(user("admin").roles("ADMIN"));
            mockMvc.perform(request.queryParam(bad[0], bad[1]))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.error.details.violations[0].field").value(bad[0]));
        }
    }

    @Test
    @DisplayName("관리자만 — 회원 403 · 익명 401")
    void adminOnly() throws Exception {
        mockMvc.perform(get(PATH).with(user("657").roles("USER"))).andExpect(status().isForbidden());
        mockMvc.perform(get(PATH)).andExpect(status().isUnauthorized());
    }

    private UUID product(String saleMode, String status, String title) {
        return fixtures.product(categoryId, saleMode, status, title, tag);
    }

    private JsonNode list(String q) throws Exception {
        return JSON.readTree(mockMvc.perform(get(PATH).queryParam("q", q).queryParam("size", "100").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("data");
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
