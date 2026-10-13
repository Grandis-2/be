package com.grandis.nova.catalog.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.grandis.nova.catalog.support.CatalogIntegrationTest;
import com.grandis.nova.catalog.support.ShopFixtures;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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

/**
 * 사진 한 장씩 넣기 · 바꾸기 · 빼기 — 등록 API 로 만든 상품(색상 블랙 · 실버 · 레드, 블랙은 둘째 장이 대표, 레드는 사진 없음, 상세 영역 둘)을 고친다.
 * 시험 CDN 주소는 https://img 다.
 */
@CatalogIntegrationTest
@AutoConfigureMockMvc
class AdminProductImageEditApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PATH = "/api/v1/admin/products";

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
    @DisplayName("넣기 — 그 색상 묶음 맨 뒤에 대표 아닌 사진으로, 빈 묶음의 첫 장은 대표. 상세는 같은 영역(공백 · 대소문자 무시)이면 그 끝, 없으면 새 영역을 맨 뒤에")
    void addAppendsToTheBundle() throws Exception {
        UUID productId = registerColorProduct();

        add(productId, "GALLERY", "실버", "https://img/s1.jpg").andExpect(status().isCreated());
        add(productId, "GALLERY", " 레드 ", "https://img/r0.jpg").andExpect(status().isCreated());
        add(productId, "DETAIL", "제품  사양", "https://img/spec1.jpg").andExpect(status().isCreated());
        add(productId, "DETAIL", "Care", "https://img/care0.jpg").andExpect(status().isCreated());
        JsonNode product = data(add(productId, "DETAIL", "CARE", "https://img/care1.jpg").andExpect(status().isCreated())).get("product");

        assertThat(gallery(product, "실버")).containsExactly("https://img/s0.jpg*", "https://img/s1.jpg");
        assertThat(gallery(product, "레드")).as("빈 묶음의 첫 장은 대표").containsExactly("https://img/r0.jpg*");
        assertThat(gallery(product, "블랙")).as("다른 묶음은 그대로").containsExactly("https://img/b0.jpg", "https://img/b1.jpg*");
        assertThat(sections(product)).as("CARE 는 Care 와 같은 영역 — 처음 이름을 남긴다").containsExactly("제품 사양", "유의사항", "Care");
        assertThat(detail(product, "제품 사양")).containsExactly("https://img/spec0.jpg", "https://img/spec1.jpg");
        assertThat(detail(product, "Care")).containsExactly("https://img/care0.jpg", "https://img/care1.jpg");
        assertThat(product.get("imageUrl").asString()).as("썸네일은 첫 색상의 첫 장 그대로").isEqualTo("https://img/b0.jpg");
    }

    /** 앱의 근사 비교 키(NFKD)는 ß 를 ss 로 펴지 않는다 — DB 콜레이션은 같다고 본다(등록의 영역 중복 판정과 같은 규칙). */
    @Test
    @DisplayName("상세 영역 이름은 DB 콜레이션으로 맞춘다 — Straße 영역이 있으면 Strasse 로 넣어도 그 영역 끝에 붙는다")
    void detailSectionMatchesByDbCollation() throws Exception {
        UUID productId = registerColorProduct();
        add(productId, "DETAIL", "Straße", "https://img/st0.jpg").andExpect(status().isCreated());
        JsonNode product = data(add(productId, "DETAIL", "Strasse", "https://img/st1.jpg").andExpect(status().isCreated())).get("product");
        assertThat(sections(product)).containsExactly("제품 사양", "유의사항", "Straße");
        assertThat(detail(product, "Straße")).containsExactly("https://img/st0.jpg", "https://img/st1.jpg");
    }

    @Test
    @DisplayName("넣기 검사 — 이미 있는 주소 · CDN 밖 · 묶음 11장째 · 없는 색상 · color 상품에 색상 빠짐 · 상세에 영역 빠짐 · 모르는 kind 는 400 이고 아무것도 바뀌지 않는다")
    void addRules() throws Exception {
        UUID productId = registerColorProduct();
        String before = options(productId);

        expectValidation(add(productId, "GALLERY", "실버", "https://img/b0.jpg"), "url");
        expectValidation(add(productId, "DETAIL", "유의사항", "https://img/s0.jpg"), "url");
        expectValidation(add(productId, "GALLERY", "실버", "https://elsewhere.example/x.jpg"), "url");
        expectValidation(add(productId, "GALLERY", "그린", "https://img/g0.jpg"), "bundle");
        expectValidation(add(productId, "GALLERY", null, "https://img/g0.jpg"), "bundle");
        expectValidation(add(productId, "DETAIL", "  ", "https://img/g0.jpg"), "bundle");
        expectValidation(mockMvc.perform(admin(post(PATH + "/{id}/images", productId))
                .content("{ \"kind\": \"BANNER\", \"bundle\": \"실버\", \"url\": \"https://img/g0.jpg\" }")), "kind");
        for (int i = 1; i < 10; i++) {
            add(productId, "GALLERY", "실버", "https://img/s-more-" + i + ".jpg").andExpect(status().isCreated());
        }
        String full = options(productId);
        expectValidation(add(productId, "GALLERY", "실버", "https://img/s-more-10.jpg"), "url");
        assertThat(options(productId)).as("거절된 넣기는 문서를 바꾸지 않는다").isEqualTo(full).isNotEqualTo(before);
    }

    @Test
    @DisplayName("color 축이 없는 상품은 기본 묶음에 넣는다 — 색상을 주면 400")
    void addToDefaultBundle() throws Exception {
        UUID productId = registerRaw("""
                { "categoryId": "%s", "saleMode": "IN_STOCK", "title": "Cable", "brand": "LG", "modelName": "C1", "visible": false, "basePrice": 1000,
                  "combinations": [ { "selections": {}, "stock": 1 } ] }
                """.formatted(categoryId));
        expectValidation(add(productId, "GALLERY", "블랙", "https://img/c0.jpg"), "bundle");
        JsonNode product = data(add(productId, "GALLERY", null, "https://img/c0.jpg").andExpect(status().isCreated())).get("product");
        assertThat(gallery(product, "")).containsExactly("https://img/c0.jpg*");
        assertThat(product.get("imageUrl").asString()).as("사진 없던 상품의 썸네일이 생긴다").isEqualTo("https://img/c0.jpg");
    }

    @Test
    @DisplayName("바꾸기 — 자리 · 대표 여부는 그대로 주소만, 첫 장이면 썸네일도 바뀐다. 없는 사진 · 이미 있는 새 주소 · CDN 밖은 400")
    void replaceKeepsPositionAndPrimary() throws Exception {
        UUID productId = registerColorProduct();

        JsonNode product = data(replace(productId, "https://img/b1.jpg", "https://img/b1-new.jpg").andExpect(status().isOk())).get("product");
        assertThat(gallery(product, "블랙")).containsExactly("https://img/b0.jpg", "https://img/b1-new.jpg*");
        product = data(replace(productId, "https://img/b0.jpg", "https://img/b0-new.jpg").andExpect(status().isOk())).get("product");
        assertThat(gallery(product, "블랙")).containsExactly("https://img/b0-new.jpg", "https://img/b1-new.jpg*");
        assertThat(product.get("imageUrl").asString()).isEqualTo("https://img/b0-new.jpg");
        product = data(replace(productId, "https://img/notice0.jpg", "https://img/notice0-new.jpg").andExpect(status().isOk())).get("product");
        assertThat(detail(product, "유의사항")).containsExactly("https://img/notice0-new.jpg");
        assertThat(jdbcTemplate.queryForObject("SELECT thumbnail_url FROM products WHERE id = UUID_TO_BIN(?)", String.class, productId.toString()))
                .as("목록 썸네일 칸도 같이").isEqualTo("https://img/b0-new.jpg");

        String before = options(productId);
        expectValidation(replace(productId, "https://img/b0.jpg", "https://img/x.jpg"), "url");
        expectValidation(replace(productId, "https://img/b0-new.jpg", "https://img/s0.jpg"), "newUrl");
        expectValidation(replace(productId, "https://img/b0-new.jpg", "https://elsewhere.example/x.jpg"), "newUrl");
        assertThat(options(productId)).isEqualTo(before);
    }

    @Test
    @DisplayName("빼기 — 대표를 빼면 남은 첫 장이 대표, 마지막 한 장도 빠진다(사진 없는 색상). 상세 영역은 비면 영역째 없앤다. 없는 사진 · 주소 없음은 400")
    void removeKeepsBundlesConsistent() throws Exception {
        UUID productId = registerColorProduct();

        JsonNode product = data(remove(productId, "https://img/b1.jpg").andExpect(status().isOk())).get("product");
        assertThat(gallery(product, "블랙")).as("대표(b1)를 빼면 남은 첫 장이 대표").containsExactly("https://img/b0.jpg*");
        product = data(remove(productId, "https://img/s0.jpg").andExpect(status().isOk())).get("product");
        assertThat(gallery(product, "실버")).as("마지막 한 장도 뺄 수 있다").isEmpty();
        product = data(remove(productId, "https://img/notice0.jpg").andExpect(status().isOk())).get("product");
        assertThat(sections(product)).as("빈 영역은 없앤다").containsExactly("제품 사양");
        product = data(remove(productId, "https://img/b0.jpg").andExpect(status().isOk())).get("product");
        assertThat(product.get("imageUrl").isNull()).as("첫 색상에 사진이 없으면 썸네일도 없다").isTrue();

        expectValidation(remove(productId, "https://img/b0.jpg"), "url");
        expectValidation(mockMvc.perform(delete(PATH + "/{id}/images", productId).with(user("admin").roles("ADMIN"))), "url");
    }

    @Test
    @DisplayName("빼기 — 대표가 아닌 장을 빼면 대표는 그대로다(첫 장이 대표로 바뀌지 않는다)")
    void removingNonPrimaryKeepsPrimary() throws Exception {
        UUID productId = registerColorProduct();
        add(productId, "GALLERY", "블랙", "https://img/b2.jpg").andExpect(status().isCreated());
        JsonNode product = data(remove(productId, "https://img/b2.jpg").andExpect(status().isOk())).get("product");
        assertThat(gallery(product, "블랙")).containsExactly("https://img/b0.jpg", "https://img/b1.jpg*");
        product = data(remove(productId, "https://img/b0.jpg").andExpect(status().isOk())).get("product");
        assertThat(gallery(product, "블랙")).containsExactly("https://img/b1.jpg*");
    }

    @Test
    @DisplayName("사전예약 오픈 뒤에도 사진은 고친다 — 같은 상품의 제목 수정은 409(대조군)")
    void imagesStayEditableAfterPreorderOpens() throws Exception {
        Instant opensAt = Instant.now().plus(Duration.ofHours(2));
        UUID productId = registerRaw(colorBody("PREORDER", """
                "campaign": { "opensAt": "%s", "closesAt": "%s" },
                "shipmentBatches": [ { "batchNumber": 1, "positionFrom": 1, "positionTo": null, "estimatedShipStart": "2026-11-01", "estimatedShipEnd": "2026-11-07" } ],
                """.formatted(opensAt, opensAt.plus(Duration.ofDays(3)))));
        Instant now = Instant.now();
        fixtures.campaign(productId, now.minus(Duration.ofHours(1)), now.plus(Duration.ofHours(1)));

        mockMvc.perform(admin(patch(PATH + "/{id}", productId)).content("{ \"title\": \"x\" }")).andExpect(status().isConflict());
        add(productId, "GALLERY", "실버", "https://img/s1.jpg").andExpect(status().isCreated());
        assertThat(galleryUrls(productId, "실버")).containsExactly("https://img/s0.jpg", "https://img/s1.jpg");
        replace(productId, "https://img/s1.jpg", "https://img/s2.jpg").andExpect(status().isOk());
        assertThat(galleryUrls(productId, "실버")).containsExactly("https://img/s0.jpg", "https://img/s2.jpg");
        remove(productId, "https://img/s2.jpg").andExpect(status().isOk());
        assertThat(galleryUrls(productId, "실버")).as("응답이 아니라 저장된 문서").containsExactly("https://img/s0.jpg");
    }

    @Test
    @DisplayName("넣기 · 바꾸기 · 빼기 모두 ADMIN 만 — 회원은 403, 익명은 401 이고 문서는 그대로. 없는 상품은 404")
    void adminOnly() throws Exception {
        UUID productId = registerColorProduct();
        String before = options(productId);
        String body = "{ \"kind\": \"GALLERY\", \"bundle\": \"실버\", \"url\": \"https://img/s1.jpg\" }";
        String replaceBody = "{ \"url\": \"https://img/s0.jpg\", \"newUrl\": \"https://img/s9.jpg\" }";
        List<java.util.function.Supplier<MockHttpServletRequestBuilder>> requests = List.of(
                () -> post(PATH + "/{id}/images", productId).contentType(MediaType.APPLICATION_JSON).content(body),
                () -> patch(PATH + "/{id}/images", productId).contentType(MediaType.APPLICATION_JSON).content(replaceBody),
                () -> delete(PATH + "/{id}/images", productId).param("url", "https://img/s0.jpg"));
        for (var request : requests) {
            mockMvc.perform(request.get().with(user("7").roles("USER"))).andExpect(status().isForbidden());
            mockMvc.perform(request.get()).andExpect(status().isUnauthorized());
        }
        assertThat(options(productId)).as("거절된 요청은 문서를 바꾸지 않는다").isEqualTo(before);
        mockMvc.perform(admin(post(PATH + "/{id}/images", UUID.randomUUID())).content(body))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    // ── 도우미 ──────────────────────────────────────────────────────────────

    private UUID registerColorProduct() throws Exception {
        return registerRaw(colorBody("IN_STOCK", ""));
    }

    private String colorBody(String saleMode, String extra) {
        String stock = "IN_STOCK".equals(saleMode) ? ", \"stock\": 1" : "";
        return """
                { "categoryId": "%s", "saleMode": "%s", "title": "Watch", "brand": "SAMSUNG", "modelName": "SM-R1", "visible": false, "basePrice": 1000,
                  "optionAxes": [ { "key": "color", "label": "색상", "values": [ { "value": "블랙" }, { "value": "실버" }, { "value": "레드" } ] } ],
                  "combinations": [ { "selections": { "color": "블랙" }%s }, { "selections": { "color": "실버" }%s }, { "selections": { "color": "레드" }%s } ],
                  %s
                  "images": {
                    "gallery": [
                      { "color": "블랙", "items": [ { "url": "https://img/b0.jpg" }, { "url": "https://img/b1.jpg", "primary": true } ] },
                      { "color": "실버", "items": [ { "url": "https://img/s0.jpg" } ] } ],
                    "detail": [
                      { "section": "제품 사양", "items": [ { "url": "https://img/spec0.jpg" } ] },
                      { "section": "유의사항", "items": [ { "url": "https://img/notice0.jpg" } ] } ] } }
                """.formatted(categoryId, saleMode, stock, stock, stock, extra);
    }

    private UUID registerRaw(String body) throws Exception {
        ResultActions created = mockMvc.perform(admin(post(PATH)).header("Idempotency-Key", "k-" + ShopFixtures.unique()).content(body))
                .andExpect(status().isCreated());
        return UUID.fromString(data(created).get("registration").get("productId").asString());
    }

    private ResultActions add(UUID productId, String kind, String bundle, String url) throws Exception {
        String bundleJson = bundle == null ? "null" : "\"" + bundle + "\"";
        return mockMvc.perform(admin(post(PATH + "/{id}/images", productId))
                .content("{ \"kind\": \"%s\", \"bundle\": %s, \"url\": \"%s\" }".formatted(kind, bundleJson, url)));
    }

    private ResultActions replace(UUID productId, String url, String newUrl) throws Exception {
        return mockMvc.perform(admin(patch(PATH + "/{id}/images", productId))
                .content("{ \"url\": \"%s\", \"newUrl\": \"%s\" }".formatted(url, newUrl)));
    }

    private ResultActions remove(UUID productId, String url) throws Exception {
        return mockMvc.perform(delete(PATH + "/{id}/images", productId).param("url", url).with(user("admin").roles("ADMIN")));
    }

    private List<String> galleryUrls(UUID productId, String color) {
        return jdbcTemplate.queryForList("""
                SELECT j.url FROM products p, JSON_TABLE(p.options, '$.axes[*]' COLUMNS (k VARCHAR(40) PATH '$.key',
                       NESTED PATH '$.values[*]' COLUMNS (v VARCHAR(60) PATH '$.normalized',
                           NESTED PATH '$.images[*]' COLUMNS (url VARCHAR(1000) PATH '$.url')))) j
                 WHERE p.id = UUID_TO_BIN(?) AND j.k = 'color' AND j.v = ? AND j.url IS NOT NULL
                """, String.class, productId.toString(), color);
    }

    private String options(UUID productId) {
        return jdbcTemplate.queryForObject("SELECT CAST(options AS CHAR) FROM products WHERE id = UUID_TO_BIN(?)", String.class, productId.toString());
    }

    /** 그 묶음의 주소 목록. 대표는 끝에 * 를 붙인다. 색상 묶음이 없으면 빈 목록. */
    private static List<String> gallery(JsonNode product, String bundleKey) {
        List<String> out = new ArrayList<>();
        for (JsonNode bundle : product.get("images").get("gallery")) {
            if (bundle.get("bundleKey").asString().equals(bundleKey)) {
                bundle.get("items").forEach(item -> out.add(item.get("url").asString() + (item.get("primary").asBoolean() ? "*" : "")));
            }
        }
        return out;
    }

    private static List<String> sections(JsonNode product) {
        List<String> out = new ArrayList<>();
        product.get("images").get("detail").forEach(section -> out.add(section.get("bundleKey").asString()));
        return out;
    }

    private static List<String> detail(JsonNode product, String section) {
        List<String> out = new ArrayList<>();
        for (JsonNode bundle : product.get("images").get("detail")) {
            if (bundle.get("bundleKey").asString().equals(section)) {
                bundle.get("items").forEach(item -> out.add(item.get("url").asString()));
            }
        }
        return out;
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
