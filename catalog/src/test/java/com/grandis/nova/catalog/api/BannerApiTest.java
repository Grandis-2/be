package com.grandis.nova.catalog.api;

import com.grandis.nova.catalog.support.AccessTokens;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 메인 배너 — 관리자 만들기 · 고치기 · 지우기 · 순서와 고객 조회(대상이 끝나면 자동 숨김). 읽기 캐시는 꺼져 있다. 사진 붙이기는 시험 대역이라
 * 주소를 따지지 않는다(ImageAttachmentsTestConfig — 실제 사진 규칙은 ImageAttachments 시험이 본다).
 */
@CatalogIntegrationTest
@AutoConfigureMockMvc
class BannerApiTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String ADMIN = "/api/v1/admin/banners";

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;

    ShopFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
        jdbcTemplate.update("DELETE FROM banners");
    }

    @Test
    @DisplayName("만들면 201 · 맨 뒤 · 대상 살아 있음, 고객 조회는 화면 칸 그대로(줄바꿈 포함)와 연결 대상을 준다")
    void createAndShow() throws Exception {
        UUID product = listedProduct();

        JsonNode created = data(create(body("아이폰 18 Pro,\nPro Max 사전예약", "PRODUCT", product, true)).andExpect(status().isCreated()));

        assertThat(created.get("position").asInt()).isZero();
        assertThat(created.get("targetLive").asBoolean()).isTrue();
        JsonNode shown = shown();
        assertThat(shown).hasSize(1);
        JsonNode banner = shown.get(0);
        assertThat(banner.get("bannerId").asString()).isEqualTo(created.get("bannerId").asString());
        assertThat(banner.get("badge").asString()).isEqualTo("사전예약");
        assertThat(banner.get("title").asString()).isEqualTo("아이폰 18 Pro,\nPro Max 사전예약");
        assertThat(banner.get("description").asString()).isEqualTo("실패 없는 사전예약부터\n순차 배송까지");
        assertThat(banner.get("tabLabel").asString()).isEqualTo("아이폰 18\n사전예약");
        assertThat(banner.get("icon").asString()).isEqualTo("Smartphone");
        assertThat(banner.get("imageUrl").asString()).isEqualTo("https://img/banner.png");
        assertThat(banner.get("imageAlt").asString()).isEqualTo("아이폰 18 Pro");
        assertThat(banner.get("target").get("type").asString()).isEqualTo("PRODUCT");
        assertThat(banner.get("target").get("id").asString()).isEqualTo(product.toString());
    }

    @Test
    @DisplayName("입력 검사 — 허용 목록 밖 아이콘 · 사전예약 상품 · 없는 상품 · 삭제한 상품 · 없는 홍보 · 빈 칸은 400")
    void validation() throws Exception {
        UUID product = listedProduct();
        UUID preorder = fixtures.product("PREORDER", "ACTIVE");
        UUID deleted = listedProduct();
        jdbcTemplate.update("UPDATE products SET visible = 0, deleted_at = UTC_TIMESTAMP(6) WHERE id = ?", (Object) UuidBinary.toBytes(deleted));

        expectField(create(body("t", "PRODUCT", product, true).replace("\"Smartphone\"", "\"Rocket\"")), "icon");
        expectField(create(body("t", "PRODUCT", preorder, true)), "target.id");
        expectField(create(body("t", "PRODUCT", UUID.randomUUID(), true)), "target.id");
        expectField(create(body("t", "PRODUCT", deleted, true)), "target.id");
        expectField(create(body("t", "PROMOTION", UUID.randomUUID(), true)), "target.id");
        create(body("", "PRODUCT", product, true)).andExpect(status().isBadRequest());
        create(body("t", "OTHER", product, true)).andExpect(status().isBadRequest());

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM banners", Integer.class)).isZero();
    }

    @Test
    @DisplayName("대상이 끝나면 자동으로 숨는다 — 상품 숨김 · 홍보 기간 끝 · 관리자가 끈 배너는 고객 조회에서 빠지고, 관리자 목록에는 남아 targetLive 로 알린다")
    void hidesWhenTargetEnds() throws Exception {
        UUID product = listedProduct();
        UUID ongoing = promotion(today().minusDays(1), today().plusDays(1));
        UUID ended = promotion(today().minusDays(5), today().minusDays(1));
        String toProduct = id(create(body("상품", "PRODUCT", product, true)));
        String toOngoing = id(create(body("진행 중 홍보", "PROMOTION", ongoing, true)));
        String toEnded = id(create(body("끝난 홍보", "PROMOTION", ended, true)));
        String off = id(create(body("꺼 둠", "PRODUCT", product, false)));

        assertThat(ids(shown())).containsExactly(toProduct, toOngoing);
        jdbcTemplate.update("UPDATE products SET visible = 0 WHERE id = ?", (Object) UuidBinary.toBytes(product));
        assertThat(ids(shown())).as("상품을 숨기면 빠진다").containsExactly(toOngoing);

        JsonNode admin = data(mockMvc.perform(get(ADMIN).with(AccessTokens.admin())).andExpect(status().isOk())).get("items");
        assertThat(ids(admin)).containsExactly(toProduct, toOngoing, toEnded, off);
        assertThat(live(admin)).containsExactly(false, true, false, false);
    }

    @Test
    @DisplayName("순서 바꾸기는 모든 배너 id 를 한 번에 — 고객 조회가 그 순서, 빠지거나 남는 id 가 있으면 400")
    void reorder() throws Exception {
        UUID product = listedProduct();
        String a = id(create(body("a", "PRODUCT", product, true)));
        String b = id(create(body("b", "PRODUCT", product, true)));
        String c = id(create(body("c", "PRODUCT", product, true)));

        mockMvc.perform(put(ADMIN + "/order").with(AccessTokens.admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"bannerIds\":[\"%s\",\"%s\",\"%s\"]}".formatted(c, a, b))).andExpect(status().isOk());

        assertThat(ids(shown())).containsExactly(c, a, b);
        mockMvc.perform(put(ADMIN + "/order").with(AccessTokens.admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"bannerIds\":[\"%s\",\"%s\"]}".formatted(c, a))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.violations[0].field").value("bannerIds"));
        mockMvc.perform(put(ADMIN + "/order").with(AccessTokens.admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"bannerIds\":[\"%s\",\"%s\",\"%s\",\"%s\"]}".formatted(c, a, b, UUID.randomUUID()))).andExpect(status().isBadRequest());
        String d = id(create(body("d", "PRODUCT", product, true)));
        assertThat(ids(shown())).as("새 배너는 맨 뒤").containsExactly(c, a, b, d);
    }

    @Test
    @DisplayName("고치면 모든 칸이 바뀌고 순서는 그대로, 지우면 204 · 고객 조회에서 빠진다. 없는 배너는 404, 대상 상품을 지웠으면 글만 고쳐도 400")
    void updateAndDelete() throws Exception {
        UUID product = listedProduct();
        UUID promotion = promotion(today(), today());
        String first = id(create(body("처음", "PRODUCT", product, true)));
        String second = id(create(body("둘째", "PRODUCT", product, true)));

        JsonNode updated = data(mockMvc.perform(put(ADMIN + "/{id}", first).with(AccessTokens.admin()).contentType(MediaType.APPLICATION_JSON)
                .content(body("고침", "PROMOTION", promotion, true))).andExpect(status().isOk()));
        assertThat(updated.get("title").asString()).isEqualTo("고침");
        assertThat(updated.get("target").get("type").asString()).isEqualTo("PROMOTION");
        assertThat(updated.get("position").asInt()).isZero();

        mockMvc.perform(delete(ADMIN + "/{id}", second).with(AccessTokens.admin())).andExpect(status().isNoContent());
        assertThat(ids(shown())).containsExactly(first);
        mockMvc.perform(delete(ADMIN + "/{id}", second).with(AccessTokens.admin())).andExpect(status().isNotFound());
        mockMvc.perform(put(ADMIN + "/{id}", UUID.randomUUID()).with(AccessTokens.admin()).contentType(MediaType.APPLICATION_JSON)
                .content(body("x", "PRODUCT", product, true))).andExpect(status().isNotFound());
        String toProduct = id(create(body("상품으로", "PRODUCT", product, true)));
        jdbcTemplate.update("UPDATE products SET visible = 0, deleted_at = UTC_TIMESTAMP(6) WHERE id = ?", (Object) UuidBinary.toBytes(product));
        expectField(mockMvc.perform(put(ADMIN + "/{id}", toProduct).with(AccessTokens.admin())
                .contentType(MediaType.APPLICATION_JSON).content(body("글만 고침", "PRODUCT", product, true))), "target.id");
    }

    @Test
    @DisplayName("고객 조회는 로그인 없이, 관리자 API 는 관리자만 — 익명 401 · 회원 403")
    void access() throws Exception {
        mockMvc.perform(get("/api/v1/banners")).andExpect(status().isOk());
        mockMvc.perform(get(ADMIN)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(ADMIN).with(AccessTokens.customer(UUID.randomUUID()))).andExpect(status().isForbidden());
        mockMvc.perform(post(ADMIN).with(AccessTokens.customer(UUID.randomUUID())).contentType(MediaType.APPLICATION_JSON)
                .content(body("x", "PRODUCT", UUID.randomUUID(), true))).andExpect(status().isForbidden());
    }

    // ── 도우미 ──

    /** 목록에 나오는 일반 상품. */
    private UUID listedProduct() {
        UUID product = fixtures.product("IN_STOCK", "ACTIVE");
        fixtures.option(product, "ACTIVE", ShopFixtures.OPTION_PRICE);
        fixtures.stockReady(product);
        return product;
    }

    private UUID promotion(LocalDate start, LocalDate end) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO promotions (id, idempotency_key, name, start_date, end_date, thumbnail_url, detail_image_urls, created_at, updated_at)
                VALUES (?, ?, '홍보', ?, ?, 'https://img/t.jpg', JSON_ARRAY(), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""",
                UuidBinary.toBytes(id), id.toString(), start, end);
        return id;
    }

    private static LocalDate today() {
        return LocalDate.now(ZoneId.of("Asia/Seoul"));
    }

    private static String body(String title, String targetType, UUID targetId, boolean visible) {
        return JSON.writeValueAsString(java.util.Map.of(
                "badge", "사전예약", "title", title, "description", "실패 없는 사전예약부터\n순차 배송까지", "tabLabel", "아이폰 18\n사전예약",
                "icon", "Smartphone", "imageUrl", "https://img/banner.png", "imageAlt", "아이폰 18 Pro",
                "target", java.util.Map.of("type", targetType, "id", targetId.toString()), "visible", visible));
    }

    private ResultActions create(String body) throws Exception {
        return mockMvc.perform(post(ADMIN).with(AccessTokens.admin()).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private void expectField(ResultActions result, String field) throws Exception {
        result.andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.details.violations[0].field").value(field));
    }

    private String id(ResultActions created) throws Exception {
        return data(created.andExpect(status().isCreated())).get("bannerId").asString();
    }

    private JsonNode shown() throws Exception {
        return data(mockMvc.perform(get("/api/v1/banners")).andExpect(status().isOk())).get("items");
    }

    private static List<String> ids(JsonNode items) {
        List<String> ids = new ArrayList<>();
        items.forEach(item -> ids.add(item.get("bannerId").asString()));
        return ids;
    }

    private static List<Boolean> live(JsonNode items) {
        List<Boolean> live = new ArrayList<>();
        items.forEach(item -> live.add(item.get("targetLive").asBoolean()));
        return live;
    }

    private static JsonNode data(ResultActions result) throws Exception {
        return JSON.readTree(result.andReturn().getResponse().getContentAsString()).get("data");
    }
}
