package com.grandis.nova.catalog.cache;

import com.grandis.nova.catalog.review.ProductReview;
import com.grandis.nova.catalog.review.ProductReviewRepository;
import com.grandis.nova.catalog.support.CatalogIntegrationTest;
import com.grandis.nova.catalog.support.RedisContainerConfig;
import com.grandis.nova.catalog.support.ShopFixtures;
import com.grandis.nova.common.UuidBinary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 읽기 캐시를 켠 채 — 공개 조회가 캐시를 거치고, catalog 의 쓰기가 비우고, 시각 칸은 꺼낼 때 다시 계산하고, Redis 가 멈춰도 답한다. */
@CatalogIntegrationTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "nova.read-cache.enabled=true")
class CatalogReadCacheApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String ADMIN = "/api/v1/admin/products";

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired StringRedisTemplate redis;
    @Autowired ProductReviewRepository reviews;

    ShopFixtures fixtures;
    UUID categoryId;

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
        categoryId = fixtures.category();
        Set<String> keys = redis.keys("catalog:cache:*");
        if (!keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    @Test
    @DisplayName("상세는 두 번째부터 캐시에서 — data 글자가 DB 에서 만든 첫 응답과 같고(봉투의 시각 · traceId 는 요청마다 다르다), DB 를 직접 고쳐도(세대 안 올림) 옛 값이다")
    void detailIsServedFromCache() throws Exception {
        UUID productId = registerInStock("캐시폰");
        String first = data(mockMvc.perform(get("/api/v1/products/{id}", productId)).andExpect(status().isOk())).toString();

        jdbcTemplate.update("UPDATE products SET title = '직접 고침' WHERE id = ?", (Object) UuidBinary.toBytes(productId));
        String second = data(mockMvc.perform(get("/api/v1/products/{id}", productId)).andExpect(status().isOk())).toString();

        assertThat(second).isEqualTo(first);
        assertThat(JSON.readTree(second).get("title").asString()).isEqualTo("캐시폰");
    }

    @Test
    @DisplayName("목록 · 카테고리 · 상품 리뷰 · 구매후기도 두 번째 응답의 data 가 첫 응답(DB)과 같다 — JSON 을 거쳐도 칸이 그대로")
    void otherPublicReadsRoundTrip() throws Exception {
        String tag = "r" + ShopFixtures.unique();
        UUID productId = registerInStock("왕복", tag);
        fixtures.childCategory(categoryId, "하위");
        reviews.saveAndFlush(ProductReview.write(productId, UUID.randomUUID(), UUID.randomUUID(), 5, "좋아요", "기본", "김**"));
        for (String path : new String[] {"/api/v1/products?q=" + tag, "/api/v1/products?sort=PRICE_ASC&size=5", "/api/v1/categories",
                "/api/v1/products/" + productId + "/reviews", "/api/v1/reviews?categoryId=" + categoryId}) {
            String first = data(mockMvc.perform(get(path)).andExpect(status().isOk())).toString();
            String second = data(mockMvc.perform(get(path)).andExpect(status().isOk())).toString();
            assertThat(second).as(path).isEqualTo(first);
        }
        assertThat(redis.keys("catalog:cache:product-list:*")).as("q 검색은 넣지 않는다 — 정렬 목록 하나만").hasSize(1);
        assertThat(redis.keys("catalog:cache:category-tree:*")).hasSize(1);
        assertThat(redis.keys("catalog:cache:reviews:*")).hasSize(2);
    }

    @Test
    @DisplayName("자유 검색어(q) · 다섯째 페이지부터는 Redis 에 넣지 않는다 — 키가 끝없이 늘어 나눠 쓰는 Redis 의 다른 키를 밀어내지 않게. 응답은 같다")
    void unboundedQueriesAreNotStored() throws Exception {
        String tag = "q" + ShopFixtures.unique();
        registerInStock("검색", tag);
        mockMvc.perform(get("/api/v1/products").param("q", tag)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/products").param("categoryId", categoryId.toString()).param("page", "5")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/reviews").param("page", "5")).andExpect(status().isOk());
        assertThat(redis.keys("catalog:cache:product-list:*")).isEmpty();
        assertThat(redis.keys("catalog:cache:reviews:*")).isEmpty();

        assertThat(data(mockMvc.perform(get("/api/v1/products").param("q", tag))).get("items").get(0).get("title").asString()).isEqualTo("검색");
        mockMvc.perform(get("/api/v1/products").param("categoryId", categoryId.toString()).param("page", "4")).andExpect(status().isOk());
        assertThat(redis.keys("catalog:cache:product-list:*")).as("넷째 페이지까지는 넣는다").hasSize(1);
    }

    @Test
    @DisplayName("max-age 는 그 값이 Redis 에서 묵은 만큼 줄어든다(초는 버림) — 앞단 캐시와 합쳐도 TTL(5초)을 넘지 않게")
    void maxAgeShrinksWithAge() throws Exception {
        UUID productId = registerInStock("나이");
        assertThat(maxAge(get("/api/v1/products/{id}", productId))).isBetween(4L, 5L);
        Thread.sleep(2_100);
        assertThat(maxAge(get("/api/v1/products/{id}", productId))).isBetween(1L, 2L);
    }

    @Test
    @DisplayName("관리자가 고치면(JPA 쓰기) 커밋 뒤 세대가 올라 상세 · 옵션 상세 · 목록이 다음 조회부터 새 값이다")
    void adminEditInvalidates() throws Exception {
        String tag = "c" + ShopFixtures.unique();
        UUID productId = registerInStock("고치기 전", tag);
        UUID variantId = UUID.fromString(data(mockMvc.perform(get("/api/v1/products/{id}", productId))).get("variants").get(0).get("variantId").asString());
        mockMvc.perform(get("/api/v1/products/{id}/variants/{v}", productId, variantId)).andExpect(status().isOk());
        assertThat(titles(tag)).containsExactly("고치기 전");

        admin(patch(ADMIN + "/{id}", productId), "{ \"title\": \"고친 뒤\", \"discountRate\": 50 }").andExpect(status().isOk());

        JsonNode detail = data(mockMvc.perform(get("/api/v1/products/{id}", productId)));
        assertThat(detail.get("title").asString()).isEqualTo("고친 뒤");
        assertThat(detail.get("discountRate").asInt()).isEqualTo(50);
        assertThat(data(mockMvc.perform(get("/api/v1/products/{id}/variants/{v}", productId, variantId))).get("price").decimalValue())
                .isEqualByComparingTo("500000");
        assertThat(titles(tag)).containsExactly("고친 뒤");
    }

    @Test
    @DisplayName("새 상품 등록도 목록 세대를 올린다 — 이미 캐시된 목록에 다음 조회부터 나온다")
    void registrationInvalidatesList() throws Exception {
        String tag = "n" + ShopFixtures.unique();
        registerInStock("첫 상품", tag);
        assertThat(titles(tag)).containsExactly("첫 상품");

        registerInStock("새 상품", tag);

        assertThat(titles(tag)).containsExactlyInAnyOrder("첫 상품", "새 상품");
    }

    @Test
    @DisplayName("사전예약 접수 단계는 꺼낼 때 지금 시각으로 — 캐시된 상세 · 목록도 오픈 시각이 지나면 OPEN")
    void preorderStatusIsRecomputed() throws Exception {
        String tag = "p" + ShopFixtures.unique();
        UUID productId = fixtures.product(categoryId, "PREORDER", "ACTIVE", "오픈 직전", tag);
        fixtures.registration(productId);
        fixtures.option(productId, "ACTIVE");
        Instant opensAt = Instant.now().plusMillis(1500);
        fixtures.campaign(productId, opensAt, opensAt.plus(Duration.ofHours(1)));

        assertThat(data(mockMvc.perform(get("/api/v1/products/{id}", productId))).get("campaign").get("status").asString()).isEqualTo("BEFORE_OPEN");
        assertThat(list(tag).get(0).get("preorderStatus").asString()).isEqualTo("BEFORE_OPEN");
        // 캐시에서 꺼낸 것인지 확인하려고 DB 를 직접 고친다(세대 안 올림)
        jdbcTemplate.update("UPDATE products SET title = '직접 고침' WHERE id = ?", (Object) UuidBinary.toBytes(productId));
        Thread.sleep(Math.max(0, Duration.between(Instant.now(), opensAt).toMillis()) + 200);

        JsonNode detail = data(mockMvc.perform(get("/api/v1/products/{id}", productId)));
        assertThat(detail.get("title").asString()).isEqualTo("오픈 직전");
        assertThat(detail.get("campaign").get("status").asString()).isEqualTo("OPEN");
        JsonNode item = list(tag).get(0);
        assertThat(item.get("title").asString()).isEqualTo("오픈 직전");
        assertThat(item.get("preorderStatus").asString()).isEqualTo("OPEN");
    }

    @Test
    @DisplayName("다른 서비스가 바꾼 것(판매 준비 완료)은 TTL 이 지나야 보인다 — 상품 목록 · 상세 TTL 5초")
    void crossServiceChangesWaitForTtl() throws Exception {
        String tag = "t" + ShopFixtures.unique();
        UUID productId = fixtures.product(categoryId, "IN_STOCK", "ACTIVE", "준비 전", tag);
        fixtures.registration(productId);
        fixtures.option(productId, "ACTIVE");
        assertThat(list(tag)).isEmpty();

        fixtures.stockReady(productId);   // order 가 재고 행을 만든 것 — catalog 는 모른다
        assertThat(list(tag)).as("TTL 안에서는 캐시된 빈 목록").isEmpty();

        Thread.sleep(CatalogReadCache.PRODUCT_TTL.toMillis() + 300);
        assertThat(list(tag)).hasSize(1);
    }

    @Test
    @DisplayName("공개 조회는 Cache-Control public + max-age(상품 5초 · 리뷰 60초 · 카테고리 5분), 관리자 조회는 그대로 no-store")
    void publicResponsesCarryCacheControl() throws Exception {
        UUID productId = registerInStock("헤더");
        // 갓 읽은 값도 남은 시간을 초로 버려 상한보다 1 작을 수 있다(늦게 보이는 쪽으로 넘지 않게)
        assertThat(maxAge(get("/api/v1/products/{id}", productId))).isBetween(4L, 5L);
        assertThat(maxAge(get("/api/v1/products"))).isBetween(4L, 5L);
        assertThat(maxAge(get("/api/v1/products/{id}/reviews", productId))).isBetween(59L, 60L);
        assertThat(maxAge(get("/api/v1/reviews"))).isBetween(59L, 60L);
        assertThat(maxAge(get("/api/v1/categories"))).isEqualTo(300L);
        mockMvc.perform(get(ADMIN + "/{id}", productId).with(user("admin").roles("ADMIN")))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
        // 오류 응답은 캐시하라고 하지 않는다
        mockMvc.perform(get("/api/v1/products/{id}", UUID.randomUUID())).andExpect(status().isNotFound())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
    }

    @Test
    @DisplayName("리뷰를 쓰면(JPA) 리뷰 세대가 오르고 상품 세대는 그대로, 상품을 고치면 둘 다 오른다")
    void reviewAndProductGenerations() throws Exception {
        UUID productId = registerInStock("세대");
        long products = generation("products");
        long reviews = generation("reviews");

        this.reviews.saveAndFlush(ProductReview.write(productId, UUID.randomUUID(), UUID.randomUUID(), 4, "리뷰", "기본", "이**"));
        assertThat(generation("products")).as("리뷰는 상품 세대를 올리지 않는다").isEqualTo(products);
        assertThat(generation("reviews")).isEqualTo(reviews + 1);

        admin(patch(ADMIN + "/{id}", productId), "{ \"title\": \"세대 2\" }").andExpect(status().isOk());
        assertThat(generation("products")).as("한 수정은 한 번만 올린다").isEqualTo(products + 1);
        assertThat(generation("reviews")).isEqualTo(reviews + 2);
    }

    @Test
    @DisplayName("Redis 가 멈춰도 공개 상세 · 목록은 DB 로 200 — 값은 DB 그대로")
    void redisDownServesFromDatabase() throws Exception {
        String tag = "d" + ShopFixtures.unique();
        UUID productId = registerInStock("장애", tag);
        mockMvc.perform(get("/api/v1/products/{id}", productId)).andExpect(status().isOk());
        jdbcTemplate.update("UPDATE products SET title = '장애 중 DB 값' WHERE id = ?", (Object) UuidBinary.toBytes(productId));

        RedisContainerConfig.pause();
        try {
            assertThat(data(mockMvc.perform(get("/api/v1/products/{id}", productId)).andExpect(status().isOk())).get("title").asString())
                    .isEqualTo("장애 중 DB 값");
            assertThat(titles(tag)).containsExactly("장애 중 DB 값");
        } finally {
            RedisContainerConfig.unpause();
        }
    }

    // ── 도우미 ──────────────────────────────────────────────────────────────

    private UUID registerInStock(String title) throws Exception {
        return registerInStock(title, "t" + ShopFixtures.unique());
    }

    /** 축 없는 일반 상품 하나(기본가 1,000,000 · 재고 3)를 관리자 API(JPA)로 등록하고 판매 준비(재고 행)를 넣는다. */
    private UUID registerInStock(String title, String tags) throws Exception {
        String body = """
                { "categoryId": "%s", "saleMode": "IN_STOCK", "title": "%s", "brand": "APPLE", "modelName": "A0001", "tags": "%s", "visible": true,
                  "basePrice": 1000000, "combinations": [ { "selections": {}, "stock": 3 } ],
                  "images": { "gallery": [ { "items": [ { "url": "https://img/c.jpg" } ] } ] } }
                """.formatted(categoryId, title, tags);
        UUID productId = UUID.fromString(data(admin(post(ADMIN).header("Idempotency-Key", "k-" + ShopFixtures.unique()), body)
                .andExpect(status().isCreated())).get("registration").get("productId").asString());
        fixtures.stockReady(productId);
        return productId;
    }

    private ResultActions admin(MockHttpServletRequestBuilder request, String body) throws Exception {
        return mockMvc.perform(request.contentType(MediaType.APPLICATION_JSON).content(body).with(user("admin").roles("ADMIN")));
    }

    /** 이 시험의 카테고리로 좁힌 목록 — 캐시되는 조회다(자유 검색어 q 는 캐시하지 않는다). tag 는 읽기 쉬우라고 받기만 한다. */
    private JsonNode list(String tag) throws Exception {
        return data(mockMvc.perform(get("/api/v1/products").param("categoryId", categoryId.toString())).andExpect(status().isOk())).get("items");
    }

    private java.util.List<String> titles(String tag) throws Exception {
        java.util.List<String> titles = new java.util.ArrayList<>();
        list(tag).forEach(item -> titles.add(item.get("title").asString()));
        return titles;
    }

    /** Cache-Control 의 max-age 초. public 이 아니면 실패. */
    private long maxAge(MockHttpServletRequestBuilder request) throws Exception {
        String value = mockMvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getHeader("Cache-Control");
        assertThat(value).matches("max-age=\\d+, public");
        return Long.parseLong(value.substring("max-age=".length(), value.indexOf(',')));
    }

    private long generation(String name) {
        String value = redis.opsForValue().get("catalog:cache:gen:" + name);
        return value == null ? 0 : Long.parseLong(value);
    }

    private static String body(ResultActions actions) throws Exception {
        return actions.andReturn().getResponse().getContentAsString();
    }

    private static JsonNode data(ResultActions actions) throws Exception {
        return JSON.readTree(body(actions)).get("data");
    }
}
