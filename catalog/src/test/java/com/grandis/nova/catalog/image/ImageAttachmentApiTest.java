package com.grandis.nova.catalog.image;

import com.grandis.nova.catalog.support.CatalogIntegrationTest;
import com.grandis.nova.catalog.support.ImageAttachmentsTestConfig;
import com.grandis.nova.catalog.support.ShopFixtures;
import com.grandis.nova.common.UuidBinary;
import org.hibernate.id.uuid.UuidVersion7Strategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 사진 붙이기 · 떼기(진짜 구현) — 사진 주소를 바꾸는 저장 경로마다 "저장 뒤 문서의 사진은 USED · 빠진 사진은 UNUSED" 이고, 사진을 바꾸지 않는
 * 경로는 상태가 그대로다. 올린 사진은 업로드 API 가 넣는 행과 같은 모양(UNUSED · {prefix}/{v7}.jpg)으로 직접 넣는다.
 */
@CatalogIntegrationTest
@AutoConfigureMockMvc
@TestPropertySource(properties = ImageAttachmentsTestConfig.REAL + "=true")
@DisplayName("사진 붙이기 · 떼기 — 저장 경로마다 USED · UNUSED")
class ImageAttachmentApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PRODUCTS = "/api/v1/admin/products";
    private static final String PROMOTIONS = "/api/v1/admin/promotions";
    private static final String CDN = "https://img/";

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ImageStore store;
    @Autowired ImageAttachments attachments;

    ShopFixtures fixtures;
    UUID categoryId;

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbc);
        categoryId = fixtures.category();
    }

    @Test
    @DisplayName("대조군 — 이 컨텍스트는 기록용 가짜가 아니라 진짜 구현이다")
    void usesRealImplementation() {
        assertThat(attachments.getClass().getName()).doesNotContain("Recording");
    }

    @Test
    @DisplayName("등록 — 문서의 사진(색상 묶음 · 상세)이 전부 USED. 같은 사진으로 다른 상품을 등록하면 그 칸의 400 이고 앞 상품은 그대로")
    void registrationAttachesEveryImage() throws Exception {
        String b0 = uploaded(ImagePurpose.PRODUCT);
        String b1 = uploaded(ImagePurpose.PRODUCT);
        String spec = uploaded(ImagePurpose.PRODUCT);
        register(colorBody(b0, b1, spec)).andExpect(status().isCreated());
        assertThat(statuses(b0, b1, spec)).containsOnly("USED");

        long products = countProducts();
        String fresh = uploaded(ImagePurpose.PRODUCT);
        expectValidation(register(colorBody(fresh, b1, uploaded(ImagePurpose.PRODUCT))), "images.gallery[0].items[1].url");
        assertThat(countProducts()).as("롤백 — 상품이 생기지 않았다").isEqualTo(products);
        assertThat(statuses(fresh)).as("롤백 — 거절된 등록의 다른 사진도 그대로").containsOnly("UNUSED");
    }

    @Test
    @DisplayName("등록 — 올린 사진이 아닌 주소는 그 칸의 400: CDN 밖 · 비슷한 호스트 · 홍보 접두 · 표에 없는 키 · 키 모양이 아님 · id 만 같고 확장자가 다름")
    void registrationRejectsNotUploaded() throws Exception {
        String ok = uploaded(ImagePurpose.PRODUCT);
        String okId = ok.substring(ok.lastIndexOf('/') + 1, ok.lastIndexOf('.'));
        List<String> rejected = List.of(
                "https://elsewhere.example/products/" + UUID.randomUUID() + ".jpg",
                "https://img.evil.test/products/" + UUID.randomUUID() + ".jpg",
                uploaded(ImagePurpose.PROMOTION),
                CDN + "products/" + UUID.randomUUID() + ".jpg",
                CDN + "products/b0.jpg",
                CDN + "products/" + okId + ".png");
        for (String url : rejected) {
            expectValidation(register(colorBody(ok, url, uploaded(ImagePurpose.PRODUCT))), "images.gallery[0].items[1].url");
        }
        assertThat(statuses(ok)).as("거절된 등록은 대조군 사진도 붙이지 않는다").containsOnly("UNUSED");
    }

    @Test
    @DisplayName("넣기 · 바꾸기 · 빼기 — 넣은 사진 USED, 바꾸면 옛것 UNUSED · 새것 USED, 빼면 UNUSED. updated_at 은 상태가 바뀔 때 새로 찍힌다")
    void imageEditsMoveStatus() throws Exception {
        String b0 = uploaded(ImagePurpose.PRODUCT);
        String b1 = uploaded(ImagePurpose.PRODUCT);
        String spec = uploaded(ImagePurpose.PRODUCT);
        UUID productId = productId(register(colorBody(b0, b1, spec)).andExpect(status().isCreated()));
        Instant attachedAt = updatedAt(b0);

        String added = uploaded(ImagePurpose.PRODUCT);
        mockMvc.perform(admin(post(PRODUCTS + "/{id}/images", productId))
                .content("{ \"kind\": \"GALLERY\", \"bundle\": \"실버\", \"url\": \"%s\" }".formatted(added))).andExpect(status().isCreated());
        assertThat(statuses(added)).containsOnly("USED");

        String replacement = uploaded(ImagePurpose.PRODUCT);
        mockMvc.perform(admin(patch(PRODUCTS + "/{id}/images", productId))
                .content("{ \"url\": \"%s\", \"newUrl\": \"%s\" }".formatted(b0, replacement))).andExpect(status().isOk());
        assertThat(statuses(b0)).containsOnly("UNUSED");
        assertThat(statuses(replacement)).containsOnly("USED");
        assertThat(updatedAt(b0)).isAfterOrEqualTo(attachedAt);

        mockMvc.perform(delete(PRODUCTS + "/{id}/images", productId).param("url", spec).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk());
        assertThat(statuses(spec)).containsOnly("UNUSED");
        assertThat(statuses(b1, added, replacement)).as("건드리지 않은 사진은 그대로").containsOnly("USED");
    }

    @Test
    @DisplayName("넣기 · 바꾸기 — 다른 상품이 쓰는 사진 · 올리지 않은 주소는 400(url · newUrl)이고 문서 · 상태가 그대로")
    void imageEditsRejectUsedOrForeign() throws Exception {
        String a0 = uploaded(ImagePurpose.PRODUCT);
        register(colorBody(a0, uploaded(ImagePurpose.PRODUCT), uploaded(ImagePurpose.PRODUCT))).andExpect(status().isCreated());
        String b0 = uploaded(ImagePurpose.PRODUCT);
        UUID other = productId(register(colorBody(b0, uploaded(ImagePurpose.PRODUCT), uploaded(ImagePurpose.PRODUCT))).andExpect(status().isCreated()));
        String before = options(other);

        expectValidation(mockMvc.perform(admin(post(PRODUCTS + "/{id}/images", other))
                .content("{ \"kind\": \"GALLERY\", \"bundle\": \"실버\", \"url\": \"%s\" }".formatted(a0))), "url");
        expectValidation(mockMvc.perform(admin(patch(PRODUCTS + "/{id}/images", other))
                .content("{ \"url\": \"%s\", \"newUrl\": \"https://elsewhere.example/x.jpg\" }".formatted(b0))), "newUrl");
        assertThat(options(other)).isEqualTo(before);
        assertThat(statuses(a0, b0)).containsOnly("USED");
    }

    @Test
    @DisplayName("사진을 바꾸지 않는 수정(표시 정보 · 값 추가 · 값 이름)은 상태 · updated_at 을 건드리지 않는다")
    void nonImageEditsAreNoOps() throws Exception {
        String b0 = uploaded(ImagePurpose.PRODUCT);
        String b1 = uploaded(ImagePurpose.PRODUCT);
        String spec = uploaded(ImagePurpose.PRODUCT);
        UUID productId = productId(register(colorBody(b0, b1, spec)).andExpect(status().isCreated()));
        List<Instant> before = List.of(updatedAt(b0), updatedAt(b1), updatedAt(spec));

        mockMvc.perform(admin(patch(PRODUCTS + "/{id}", productId)).content("{ \"title\": \"Watch 2\" }")).andExpect(status().isOk());
        mockMvc.perform(admin(post(PRODUCTS + "/{id}/option-values", productId)).content("{ \"axisKey\": \"color\", \"value\": \"블루\" }"))
                .andExpect(status().isCreated());
        mockMvc.perform(admin(patch(PRODUCTS + "/{id}/option-values/{v}", productId, fixtures.valueId(productId, "color", "블랙")))
                .content("{ \"value\": \"Black\" }")).andExpect(status().isOk());

        assertThat(statuses(b0, b1, spec)).containsOnly("USED");
        assertThat(List.of(updatedAt(b0), updatedAt(b1), updatedAt(spec))).isEqualTo(before);
    }

    @Test
    @DisplayName("홍보 — 썸네일과 상세의 같은 사진은 받아 한 번 USED, 고치면 빠진 사진 UNUSED. 상품 접두 사진은 400(thumbnailUrl · detailImageUrls[i])")
    void promotionAttachesUnion() throws Exception {
        UUID product = preorder();
        String thumb = uploaded(ImagePurpose.PROMOTION);
        String detail = uploaded(ImagePurpose.PROMOTION);
        UUID promotionId = UUID.fromString(data(createPromotion(promotionBody(thumb, List.of(thumb, detail), product))
                .andExpect(status().isCreated())).get("promotionId").asString());
        assertThat(statuses(thumb, detail)).containsOnly("USED");

        String newThumb = uploaded(ImagePurpose.PROMOTION);
        mockMvc.perform(put(PROMOTIONS + "/{id}", promotionId).contentType(MediaType.APPLICATION_JSON)
                .content(promotionBody(newThumb, List.of(thumb), product)).with(user("admin").roles("ADMIN"))).andExpect(status().isOk());
        assertThat(statuses(newThumb, thumb)).as("썸네일에서 빠졌지만 상세에 남은 사진은 USED").containsOnly("USED");
        assertThat(statuses(detail)).containsOnly("UNUSED");

        expectValidation(createPromotion(promotionBody(uploaded(ImagePurpose.PRODUCT), List.of(), preorder())), "thumbnailUrl");
        expectValidation(createPromotion(promotionBody(uploaded(ImagePurpose.PROMOTION), List.of(detail, uploaded(ImagePurpose.PRODUCT)), preorder())),
                "detailImageUrls[1]");
    }

    @Test
    @DisplayName("같은 Idempotency-Key 로 동시에 두 번 등록해도 400 이 나지 않는다 — 뒤 요청은 앞 요청의 멱등 키에서 기다려 처리 중 · 재생으로 끝난다")
    void sameKeyConcurrentRegistrationIsNotRejectedAsUsed() throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            for (int round = 0; round < 10; round++) {
                String body = colorBody(uploaded(ImagePurpose.PRODUCT), uploaded(ImagePurpose.PRODUCT), uploaded(ImagePurpose.PRODUCT));
                String key = "k-" + ShopFixtures.unique();
                List<Future<Integer>> results = new ArrayList<>();
                for (int i = 0; i < 2; i++) {
                    Callable<Integer> send = () -> register(key, body).andReturn().getResponse().getStatus();
                    results.add(pool.submit(send));
                }
                List<Integer> codes = List.of(results.get(0).get(), results.get(1).get());
                assertThat(codes).as("round %d", round).contains(201).doesNotContain(400, 500);
            }
        }
    }

    @Test
    @DisplayName("같은 올린 사진을 두 상품에 동시에 넣으면 하나만 붙는다 — 하나 201 · 하나 400, 교착이면 409 retryable(500 없음)")
    void concurrentAttachOfTheSameImageHasOneWinner() throws Exception {
        UUID first = productId(register(colorBody(uploaded(ImagePurpose.PRODUCT), uploaded(ImagePurpose.PRODUCT), uploaded(ImagePurpose.PRODUCT))));
        UUID second = productId(register(colorBody(uploaded(ImagePurpose.PRODUCT), uploaded(ImagePurpose.PRODUCT), uploaded(ImagePurpose.PRODUCT))));
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            for (int round = 0; round < 10; round++) {
                String url = uploaded(ImagePurpose.PRODUCT);
                List<Future<Integer>> results = new ArrayList<>();
                for (UUID productId : List.of(first, second)) {
                    Callable<Integer> add = () -> mockMvc.perform(admin(post(PRODUCTS + "/{id}/images", productId))
                            .content("{ \"kind\": \"DETAIL\", \"bundle\": \"추가\", \"url\": \"%s\" }".formatted(url)))
                            .andReturn().getResponse().getStatus();
                    results.add(pool.submit(add));
                }
                List<Integer> codes = List.of(results.get(0).get(), results.get(1).get());
                assertThat(codes).as("round %d", round).containsOnlyOnce(201).doesNotContain(500);
                assertThat(statuses(url)).containsOnly("USED");
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM products WHERE JSON_SEARCH(options, 'one', ?) IS NOT NULL", Long.class, url))
                        .as("문서에 실린 곳도 하나").isEqualTo(1L);
            }
        }
    }

    // ── 도우미 ─────────────────────────────────────────────

    /** 업로드 API 가 넣는 행과 같은 모양의 올린 사진 하나. */
    private String uploaded(ImagePurpose purpose) {
        UUID id = UuidVersion7Strategy.INSTANCE.generateUuid(null);
        String key = ImageKeys.key(purpose, id, ImageFormat.JPEG);
        store.insertUnused(id, key, Instant.now());
        return CDN + key;
    }

    private List<String> statuses(String... urls) {
        List<String> out = new ArrayList<>();
        for (String url : urls) {
            UUID id = ImageKeys.idOf(ImageKeys.keyOf(url).orElseThrow()).orElseThrow();
            out.add(jdbc.queryForObject("SELECT status FROM images WHERE id = ?", String.class, (Object) UuidBinary.toBytes(id)));
        }
        return out;
    }

    private Instant updatedAt(String url) {
        UUID id = ImageKeys.idOf(ImageKeys.keyOf(url).orElseThrow()).orElseThrow();
        return jdbc.queryForObject("SELECT updated_at FROM images WHERE id = ?", java.time.LocalDateTime.class, (Object) UuidBinary.toBytes(id))
                .toInstant(java.time.ZoneOffset.UTC);
    }

    /** 색상(블랙 · 실버) 일반 상품 — 블랙에 두 장, 상세 한 장. */
    private String colorBody(String black0, String black1, String spec) {
        return """
                { "categoryId": "%s", "saleMode": "IN_STOCK", "title": "Watch", "brand": "SAMSUNG", "modelName": "SM-R1", "visible": false, "basePrice": 1000,
                  "optionAxes": [ { "key": "color", "label": "색상", "values": [ { "value": "블랙" }, { "value": "실버" } ] } ],
                  "combinations": [ { "selections": { "color": "블랙" }, "stock": 1 }, { "selections": { "color": "실버" }, "stock": 1 } ],
                  "images": {
                    "gallery": [ { "color": "블랙", "items": [ { "url": "%s" }, { "url": "%s" } ] } ],
                    "detail": [ { "section": "제품 사양", "items": [ { "url": "%s" } ] } ] } }
                """.formatted(categoryId, black0, black1, spec);
    }

    private ResultActions register(String body) throws Exception {
        return register("k-" + ShopFixtures.unique(), body);
    }

    private ResultActions register(String key, String body) throws Exception {
        return mockMvc.perform(admin(post(PRODUCTS)).header("Idempotency-Key", key).content(body));
    }

    private UUID productId(ResultActions created) throws Exception {
        return UUID.fromString(data(created).get("registration").get("productId").asString());
    }

    private UUID preorder() {
        Instant now = Instant.now();
        UUID productId = fixtures.product(categoryId, "PREORDER", "ACTIVE", "홍보용", "t");
        fixtures.registration(productId);
        fixtures.option(productId, "ACTIVE", new BigDecimal("1250000"));
        fixtures.campaign(productId, now.plus(Duration.ofHours(1)), now.plus(Duration.ofHours(3)));
        return productId;
    }

    private static String promotionBody(String thumbnail, List<String> details, UUID productId) {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        List<String> quoted = details.stream().map(url -> "\"" + url + "\"").toList();
        return """
                { "name": "홍보", "startDate": "%s", "endDate": "%s", "thumbnailUrl": "%s", "detailImageUrls": [%s], "productIds": ["%s"] }
                """.formatted(today, today, thumbnail, String.join(", ", quoted), productId);
    }

    private ResultActions createPromotion(String body) throws Exception {
        return mockMvc.perform(post(PROMOTIONS).header("Idempotency-Key", "k-" + ShopFixtures.unique()).contentType(MediaType.APPLICATION_JSON)
                .content(body).with(user("admin").roles("ADMIN")));
    }

    private String options(UUID productId) {
        return jdbc.queryForObject("SELECT CAST(options AS CHAR) FROM products WHERE id = UUID_TO_BIN(?)", String.class, productId.toString());
    }

    private long countProducts() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM products", Long.class);
    }

    private static MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder builder) {
        return builder.contentType(MediaType.APPLICATION_JSON).with(user("admin").roles("ADMIN"));
    }

    private static void expectValidation(ResultActions actions, String field) throws Exception {
        actions.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details.violations[0].field").value(field));
    }

    private static tools.jackson.databind.JsonNode data(ResultActions actions) throws Exception {
        return JSON.readTree(actions.andReturn().getResponse().getContentAsString()).get("data");
    }
}
