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
    @Autowired CatalogLocalCaches local;
    @Autowired java.util.List<com.grandis.nova.catalog.event.CatalogEventHandler> handlers;

    ShopFixtures fixtures;
    UUID categoryId;

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
        categoryId = fixtures.category();
        Set<String> keys = redis.keys("catalog:cache:*");
        keys.addAll(redis.keys("catalog:views:*"));
        keys.addAll(redis.keys("catalog:signals:*"));
        if (!keys.isEmpty()) {
            redis.delete(keys);
        }
        local.productList().invalidateAll();
        local.productRanking().invalidateAll();
        local.categoryTree().invalidateAll();
    }

    /** 메모리 캐시에 든 키 수(정리 뒤). */
    private static long localSize(LocalReadCache<?, ?> cache) {
        cache.cache().cleanUp();
        return cache.cache().estimatedSize();
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
    @DisplayName("목록(색상칩 포함) · 카테고리 · 상품 리뷰 · 구매후기도 두 번째 응답의 data 가 첫 응답(DB)과 같다 — JSON 을 거쳐도 칸이 그대로")
    void otherPublicReadsRoundTrip() throws Exception {
        String tag = "r" + ShopFixtures.unique();
        UUID productId = registerInStock("왕복", tag);
        UUID colored = registerWithColor("왕복 색상");   // 색상칩(중첩 레코드 목록)도 JSON 을 거쳐 그대로
        fixtures.childCategory(categoryId, "하위");
        reviews.saveAndFlush(ProductReview.write(productId, UUID.randomUUID(), UUID.randomUUID(), 5, "좋아요", "기본", "김**"));
        for (String path : new String[] {"/api/v1/products?q=" + tag, "/api/v1/products?categoryId=" + categoryId + "&sort=PRICE_ASC&size=12", "/api/v1/categories",
                "/api/v1/products/" + productId + "/reviews", "/api/v1/reviews?categoryId=" + categoryId}) {
            String first = data(mockMvc.perform(get(path)).andExpect(status().isOk())).toString();
            String second = data(mockMvc.perform(get(path)).andExpect(status().isOk())).toString();
            assertThat(second).as(path).isEqualTo(first);
        }
        java.util.List<String> chips = new java.util.ArrayList<>();
        data(mockMvc.perform(get("/api/v1/products?categoryId=" + categoryId + "&sort=PRICE_ASC&size=12"))).get("items").forEach(item -> {
            if (item.get("productId").asString().equals(colored.toString())) {
                item.get("colors").forEach(chip -> chips.add(chip.get("label").asString()));
            }
        });
        assertThat(chips).as("캐시에서 꺼낸 목록의 그 상품에 색상칩이 실렸다").containsExactly("화이트");
        assertThat(localSize(local.productList())).as("q 검색은 넣지 않는다 — 정렬 목록 하나만(메모리)").isEqualTo(1);
        assertThat(localSize(local.categoryTree())).isEqualTo(1);
        assertThat(redis.keys("catalog:cache:product-list:*")).as("목록은 Redis 에 넣지 않는다").isEmpty();
        assertThat(redis.keys("catalog:cache:reviews:*")).hasSize(2);
    }

    @Test
    @DisplayName("입력이 열린 조회는 캐시에 넣지 않는다 — 검색어 · 색상 · 용량 · 다섯째 이후 페이지 · 화면이 안 쓰는 size · 빈 결과(없는 카테고리 · 없는 상품). 응답은 같다")
    void openInputsAreNotStored() throws Exception {
        String tag = "q" + ShopFixtures.unique();
        UUID productId = registerInStock("검색", tag);
        String category = categoryId.toString();
        String[][] requests = {
                {"q", tag}, {"color", "cache-probe-" + ShopFixtures.unique()}, {"storage", "9TB"}, {"page", "5"}, {"size", "37"}};
        for (String[] param : requests) {
            mockMvc.perform(get("/api/v1/products").param("categoryId", category).param(param[0], param[1])).andExpect(status().isOk());
        }
        mockMvc.perform(get("/api/v1/products").param("categoryId", UUID.randomUUID().toString())).andExpect(status().isOk());   // 없는 카테고리 — 빈 목록
        // 결과가 있는 색상 조회도 넣지 않는다 — 있는 색상에 아무 문자열을 OR 로 붙여 키를 끝없이 만들 수 있다
        UUID black = fixtures.product(categoryId, "IN_STOCK", "ACTIVE", "블랙폰", tag);
        fixtures.registration(black);
        fixtures.optionWithAttributes(black, "ACTIVE", new java.math.BigDecimal("1000"), "블랙", "{\"color\":\"블랙\"}");
        fixtures.stockReady(black);
        JsonNode colored = data(mockMvc.perform(get("/api/v1/products").param("categoryId", category)
                .param("color", "블랙", "probe-" + ShopFixtures.unique())).andExpect(status().isOk())).get("items");
        assertThat(colored).as("결과가 있는 조회").hasSize(1);
        mockMvc.perform(get("/api/v1/reviews").param("categoryId", UUID.randomUUID().toString())).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/products/{id}/reviews", UUID.randomUUID())).andExpect(status().isNotFound());   // 없는 상품
        mockMvc.perform(get("/api/v1/products/{id}/reviews", productId).param("size", "37")).andExpect(status().isOk());
        assertThat(localSize(local.productList())).isZero();
        assertThat(redis.keys("catalog:cache:reviews:*")).isEmpty();
        java.util.List<String> searched = new java.util.ArrayList<>();
        data(mockMvc.perform(get("/api/v1/products").param("q", tag))).get("items").forEach(item -> searched.add(item.get("title").asString()));
        assertThat(searched).contains("검색");

        // 닫힌 입력은 넣는다 — 넷째 페이지까지, 브랜드는 순서만 달라도 같은 키
        mockMvc.perform(get("/api/v1/products").param("categoryId", category).param("brand", "APPLE", "LG")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/products").param("categoryId", category).param("brand", "LG", "APPLE")).andExpect(status().isOk());
        assertThat(localSize(local.productList())).isEqualTo(1);
    }

    @Test
    @DisplayName("max-age 는 그 값이 읽기 캐시에서 묵은 만큼 줄어든다(초는 버림) — 앞단 캐시와 합쳐도 TTL(5초)을 넘지 않게")
    void maxAgeShrinksWithAge() throws Exception {
        registerInStock("나이");
        MockHttpServletRequestBuilder listing = get("/api/v1/products").param("categoryId", categoryId.toString());
        assertThat(maxAge(listing)).isBetween(4L, 5L);
        Thread.sleep(2_100);
        assertThat(maxAge(listing)).isBetween(1L, 2L);
    }

    @Test
    @DisplayName("상세는 본문만 캐시하고 재고 · 판매 준비 · 회차는 요청마다 읽는다 — 본문이 캐시에서 나와도 재고 수량 · 품절 · 조기 마감 · 판매 준비가 바로 바뀐다")
    void detailLivePartsAreNotCached() throws Exception {
        UUID productId = registerInStock("실시간");   // 재고 행 0 — 품절
        JsonNode first = data(mockMvc.perform(get("/api/v1/products/{id}", productId)).andExpect(status().isOk()));
        assertThat(first.get("soldOut").asBoolean()).isTrue();
        UUID optionId = UUID.fromString(first.get("variants").get(0).get("variantId").asString());
        jdbcTemplate.update("UPDATE products SET title = '직접 고침' WHERE id = ?", (Object) UuidBinary.toBytes(productId));
        jdbcTemplate.update("UPDATE option_inventories SET stock_total = 7 WHERE option_id = ?", (Object) UuidBinary.toBytes(optionId));   // order 가 재고를 바꿨다

        JsonNode second = data(mockMvc.perform(get("/api/v1/products/{id}", productId)).andExpect(status().isOk()));
        assertThat(second.get("title").asString()).as("본문은 캐시에서").isEqualTo("실시간");
        assertThat(second.get("variants").get(0).get("availableQuantity").asInt()).isEqualTo(7);
        assertThat(second.get("soldOut").asBoolean()).isFalse();
        assertThat(data(mockMvc.perform(get("/api/v1/products/{id}/variants/{v}", productId, optionId))).get("availableQuantity").asInt())
                .isEqualTo(7);

        // 사전예약: 판매 준비 전 404 → 회차 행이 생기면 바로 200, 조기 마감도 바로
        String tag = "lv" + ShopFixtures.unique();
        UUID preorder = fixtures.product(categoryId, "PREORDER", "ACTIVE", "곧 열림", tag);
        fixtures.registration(preorder);
        fixtures.option(preorder, "ACTIVE");
        mockMvc.perform(get("/api/v1/products/{id}", preorder)).andExpect(status().isNotFound());
        Instant now = Instant.now();
        fixtures.campaign(preorder, now.minus(Duration.ofHours(1)), now.plus(Duration.ofHours(1)));
        assertThat(data(mockMvc.perform(get("/api/v1/products/{id}", preorder)).andExpect(status().isOk())).get("campaign").get("status").asString())
                .isEqualTo("OPEN");
        jdbcTemplate.update("UPDATE preorder_campaigns SET closes_at = UTC_TIMESTAMP(6) - INTERVAL 1 MINUTE WHERE product_id = ?",
                (Object) UuidBinary.toBytes(preorder));   // preorder 가 조기 마감했다
        assertThat(data(mockMvc.perform(get("/api/v1/products/{id}", preorder))).get("campaign").get("status").asString()).isEqualTo("CLOSED");
        assertThat(redis.keys("catalog:cache:product-detail-body:*")).as("두 상품의 본문만 캐시").hasSize(2);
    }

    @Test
    @DisplayName("일반 상품 상세가 캐시에서 나갈 때 DB 의 상품 표를 읽지 않는다 — 판매 준비는 본문과 같이 캐시된다")
    void cachedInStockDetailDoesNotReadProducts() throws Exception {
        UUID productId = registerInStock("준비캐시");
        UUID uncached = registerInStock("캐시 없음");
        mockMvc.perform(get("/api/v1/products/{id}", productId)).andExpect(status().isOk());   // 본문을 채운다

        java.util.regex.Pattern products = java.util.regex.Pattern.compile("\\bproducts\\b");
        String uncachedId = java.util.HexFormat.of().formatHex(UuidBinary.toBytes(uncached));
        assertThat(appStatements(() -> mockMvc.perform(get("/api/v1/products/{id}", uncached)).andExpect(status().isOk())))
                .as("대조군 — 캐시에 없는 상세는 상품 표를 읽는다(같은 계기로 잡힌다)")
                .anyMatch(sql -> products.matcher(sql).find() && sql.contains(uncachedId));
        assertThat(appStatements(() -> mockMvc.perform(get("/api/v1/products/{id}", productId)).andExpect(status().isOk())))
                .as("캐시된 상세의 요청 동안 상품 표를 읽은 문장").noneMatch(sql -> products.matcher(sql).find());
    }

    interface ThrowingRunnable {
        void run() throws Exception;
    }

    /** 호출 동안 앱 사용자(root 아님)가 보낸 문장(일반 로그). 창마다 로그를 비우고 잰다. */
    private static java.util.List<String> appStatements(ThrowingRunnable call) throws Exception {
        java.util.List<String> statements = new java.util.ArrayList<>();
        var mysql = com.grandis.nova.catalog.support.MySqlContainerConfig.get();
        try (java.sql.Connection root = java.sql.DriverManager.getConnection(mysql.getJdbcUrl(), "root", mysql.getPassword());
             java.sql.Statement statement = root.createStatement()) {
            statement.execute("SET GLOBAL log_output = 'TABLE'");
            statement.execute("SET GLOBAL general_log = 'ON'");
            statement.execute("TRUNCATE TABLE mysql.general_log");
            try {
                call.run();
            } finally {
                statement.execute("SET GLOBAL general_log = 'OFF'");
            }
            try (java.sql.ResultSet rs = statement.executeQuery("SELECT argument FROM mysql.general_log WHERE command_type = 'Query'"
                    + " AND user_host NOT LIKE 'root%' ORDER BY event_time")) {
                while (rs.next()) {
                    statements.add(new String(rs.getBytes(1), java.nio.charset.StandardCharsets.UTF_8));
                }
            }
        }
        return statements;
    }

    @Test
    @DisplayName("상세 본문의 Redis TTL 은 10분 — 응답 max-age 는 실시간 칸 때문에 5초 상한")
    void detailBodyTtlIsLong() throws Exception {
        UUID productId = registerInStock("길게");
        assertThat(maxAge(get("/api/v1/products/{id}", productId))).isBetween(4L, 5L);
        java.util.Set<String> keys = redis.keys("catalog:cache:product-detail-body:*" + productId);
        assertThat(keys).as("본문이 캐시에 들어갔다").hasSize(1);
        String key = keys.iterator().next();
        assertThat(redis.getExpire(key)).isBetween(590L, 600L);

        // 본문이 30초 묵어도 max-age 는 5초 상한 그대로 — 남은 시간은 본문 TTL(10분)로 센다(상품 TTL 5초로 세면 0)
        tools.jackson.databind.node.ObjectNode stored = (tools.jackson.databind.node.ObjectNode) JSON.readTree(redis.opsForValue().get(key));
        stored.put("loadedAt", System.currentTimeMillis() - 30_000);
        redis.opsForValue().set(key, stored.toString(), Duration.ofMinutes(9));
        assertThat(maxAge(get("/api/v1/products/{id}", productId))).isBetween(4L, 5L);
        UUID variantId = UUID.fromString(data(mockMvc.perform(get("/api/v1/products/{id}", productId))).get("variants").get(0).get("variantId").asString());
        assertThat(maxAge(get("/api/v1/products/{id}/variants/{v}", productId, variantId))).isBetween(4L, 5L);
    }

    @Test
    @DisplayName("홍보 — 본문은 캐시에서(DB 를 직접 고쳐도 옛 값), 관리자가 고치면 바로 새 값, 상품 카드(회차 · 할인가)는 요청마다 읽는다")
    void promotionBodyCachedCardsLive() throws Exception {
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));
        UUID productId = fixtures.product(categoryId, "PREORDER", "ACTIVE", "홍보 상품", "t");
        fixtures.registration(productId);
        fixtures.option(productId, "ACTIVE", new java.math.BigDecimal("1000000"));
        Instant now = Instant.now();
        fixtures.campaign(productId, now.minus(Duration.ofHours(1)), now.plus(Duration.ofHours(1)));
        String body = """
                { "name": "첫 이름", "startDate": "%s", "endDate": "%s", "thumbnailUrl": "https://img/t.jpg", "detailImageUrls": [], "productIds": ["%s"] }
                """.formatted(today, today, productId);
        UUID promotionId = UUID.fromString(data(admin(post("/api/v1/admin/promotions").header("Idempotency-Key", "k-" + ShopFixtures.unique()), body)
                .andExpect(status().isCreated())).get("promotionId").asString());

        JsonNode first = data(mockMvc.perform(get("/api/v1/promotions/{id}", promotionId)).andExpect(status().isOk()));
        assertThat(first.get("products").get(0).get("preorderStatus").asString()).isEqualTo("OPEN");
        assertThat(titlesOfPromotions()).contains("첫 이름");   // 목록도 캐시에 채운다
        jdbcTemplate.update("UPDATE promotions SET name = '직접 고침' WHERE id = ?", (Object) UuidBinary.toBytes(promotionId));
        jdbcTemplate.update("UPDATE preorder_campaigns SET closes_at = UTC_TIMESTAMP(6) - INTERVAL 1 MINUTE WHERE product_id = ?",
                (Object) UuidBinary.toBytes(productId));   // 조기 마감
        jdbcTemplate.update("UPDATE products SET discount_rate = 50 WHERE id = ?", (Object) UuidBinary.toBytes(productId));

        JsonNode second = data(mockMvc.perform(get("/api/v1/promotions/{id}", promotionId)).andExpect(status().isOk()));
        assertThat(second.get("name").asString()).as("본문은 캐시에서").isEqualTo("첫 이름");
        assertThat(second.get("products").get(0).get("preorderStatus").asString()).isEqualTo("CLOSED");
        assertThat(second.get("products").get(0).get("minPrice").decimalValue()).isEqualByComparingTo("500000");
        assertThat(titlesOfPromotions()).contains("첫 이름");

        admin(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/admin/promotions/{id}", promotionId),
                body.replace("첫 이름", "고친 이름")).andExpect(status().isOk());
        assertThat(data(mockMvc.perform(get("/api/v1/promotions/{id}", promotionId))).get("name").asString()).isEqualTo("고친 이름");
        assertThat(titlesOfPromotions()).contains("고친 이름").doesNotContain("첫 이름");

        // 새로 만든 홍보도 세대를 올린다 — 캐시된 목록에 다음 조회부터 나온다
        UUID another = fixtures.product(categoryId, "PREORDER", "ACTIVE", "홍보 상품 2", "t");
        fixtures.registration(another);
        fixtures.option(another, "ACTIVE");
        fixtures.campaign(another, now.minus(Duration.ofHours(1)), now.plus(Duration.ofHours(1)));
        admin(post("/api/v1/admin/promotions").header("Idempotency-Key", "k-" + ShopFixtures.unique()),
                body.replace("첫 이름", "새 홍보").replace(productId.toString(), another.toString())).andExpect(status().isCreated());
        assertThat(titlesOfPromotions()).contains("고친 이름", "새 홍보");
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
    @DisplayName("공개 전환 · 판매 상태 · 사진 넣기 · 옵션 값 추가도 커밋 뒤 세대를 올린다 — 캐시된 상세가 다음 조회부터 새 값(비공개는 404)")
    void otherAdminWritesInvalidate() throws Exception {
        UUID paused = registerInStock("판매 중지");
        mockMvc.perform(get("/api/v1/products/{id}", paused)).andExpect(status().isOk());
        admin(patch(ADMIN + "/{id}/sale-status", paused), "{ \"status\": \"PAUSED\" }").andExpect(status().isOk());
        assertThat(data(mockMvc.perform(get("/api/v1/products/{id}", paused))).get("status").asString()).isEqualTo("PAUSED");

        UUID imaged = registerInStock("사진");
        mockMvc.perform(get("/api/v1/products/{id}", imaged)).andExpect(status().isOk());
        String url = "https://img/added-" + ShopFixtures.unique() + ".jpg";
        admin(post(ADMIN + "/{id}/images", imaged), "{ \"kind\": \"DETAIL\", \"bundle\": \"스펙\", \"url\": \"%s\" }".formatted(url))
                .andExpect(status().isCreated());
        assertThat(data(mockMvc.perform(get("/api/v1/products/{id}", imaged))).toString()).contains(url);

        UUID colored = registerWithColor("색 추가");
        assertThat(data(mockMvc.perform(get("/api/v1/products/{id}", colored))).toString()).doesNotContain("블루");
        admin(post(ADMIN + "/{id}/option-values", colored), "{ \"axisKey\": \"color\", \"value\": \"블루\" }").andExpect(status().isCreated());
        assertThat(data(mockMvc.perform(get("/api/v1/products/{id}", colored))).toString()).contains("블루");

        UUID hidden = registerInStock("숨김");
        mockMvc.perform(get("/api/v1/products/{id}", hidden)).andExpect(status().isOk());
        admin(patch(ADMIN + "/{id}/visibility", hidden), "{ \"visible\": false }").andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/products/{id}", hidden)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("판매 준비 이벤트를 처리하면(엔티티를 거치지 않는 쓰기) 이 인스턴스의 메모리 목록을 바로 버린다 — 새 상품이 TTL 을 기다리지 않고 나온다")
    void stockReadyEventDropsMemoryList() throws Exception {
        // 빈 목록은 메모리에 넣지 않는다 — 같은 카테고리에 이미 보이는 상품을 두어 이벤트 전 목록이 메모리에 들어가게 한다
        UUID shown = registerInStock("이미 보임");
        String title = "준비 이벤트 " + ShopFixtures.unique();
        UUID product = fixtures.product(categoryId, "IN_STOCK", "ACTIVE", title, "t" + ShopFixtures.unique());
        fixtures.inventoryOnly(fixtures.option(product, "ACTIVE", ShopFixtures.OPTION_PRICE), 3, 0, 0);
        assertThat(list("").findValues("title").stream().map(JsonNode::asString).toList()).as("이벤트 전 — 준비 전이라 없다")
                .contains("이미 보임").doesNotContain(title);
        jdbcTemplate.update("UPDATE products SET title = '직접 고침' WHERE id = ?", (Object) UuidBinary.toBytes(shown));
        assertThat(list("").findValues("title").stream().map(JsonNode::asString).toList()).as("대조군 — 메모리의 옛 목록이다").contains("이미 보임");

        handlers.stream().filter(h -> h.eventType().equals("IN_STOCK_PRODUCT_READY")).findFirst().orElseThrow().handle(product, null);

        assertThat(list("").findValues("title").stream().map(JsonNode::asString).toList()).as("버리고 다시 읽었다").contains(title, "직접 고침");
    }

    @Test
    @DisplayName("판매 준비 전인 일반 상품의 상세 본문은 캐시에 넣지 않는다 — 준비되면(이벤트든 DB 든) 바로 200, 준비된 본문은 캐시에 들고 같은 이벤트가 다시 와도 200")
    void notReadyDetailIsNotCached() throws Exception {
        UUID product = fixtures.product(categoryId, "IN_STOCK", "ACTIVE", "준비 상세", "t" + ShopFixtures.unique());
        fixtures.inventoryOnly(fixtures.option(product, "ACTIVE", ShopFixtures.OPTION_PRICE), 3, 0, 0);
        UUID direct = fixtures.product(categoryId, "IN_STOCK", "ACTIVE", "직접 준비", "t" + ShopFixtures.unique());
        fixtures.inventoryOnly(fixtures.option(direct, "ACTIVE", ShopFixtures.OPTION_PRICE), 3, 0, 0);
        mockMvc.perform(get("/api/v1/products/{id}", product)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/products/{id}", direct)).andExpect(status().isNotFound());
        assertThat(redis.keys("catalog:cache:product-detail-body:*")).as("준비 전 본문은 넣지 않는다").isEmpty();

        fixtures.stockReady(direct);   // 세대를 올리지 않는 쓰기(예: 마이그레이션)여도 묵은 404 가 없다
        mockMvc.perform(get("/api/v1/products/{id}", direct)).andExpect(status().isOk());

        var handler = handlers.stream().filter(h -> h.eventType().equals("IN_STOCK_PRODUCT_READY")).findFirst().orElseThrow();
        handler.handle(product, null);
        mockMvc.perform(get("/api/v1/products/{id}", product)).andExpect(status().isOk());
        assertThat(redis.keys("catalog:cache:product-detail-body:*" + product)).as("준비된 본문은 캐시에 든다").hasSize(1);
        handler.handle(product, null);   // 같은 이벤트가 다시 와도(중복 · 재전송)
        mockMvc.perform(get("/api/v1/products/{id}", product)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("조회수 랭킹은 메모리에 둔다 — DB 를 직접 고치면 옛 카드, 관리자가 상품을 숨기면(JPA) 이 인스턴스는 바로 빠진다")
    void productRankingIsDroppedWhenProductChanges() throws Exception {
        UUID kept = registerInStock("랭킹 유지");
        UUID hidden = registerInStock("랭킹 숨김");
        for (UUID product : new UUID[]{kept, hidden}) {
            mockMvc.perform(post("/api/v1/products/{id}/views", product).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"anonymousId\":\"%s\"}".formatted(UUID.randomUUID()))).andExpect(status().isNoContent());
        }
        assertThat(rankingTitles()).containsExactlyInAnyOrder("랭킹 유지", "랭킹 숨김");
        jdbcTemplate.update("UPDATE products SET title = '직접 고침' WHERE id = ?", (Object) UuidBinary.toBytes(kept));

        assertThat(rankingTitles()).as("메모리의 옛 카드 — 캐시가 켜져 있다").containsExactlyInAnyOrder("랭킹 유지", "랭킹 숨김");
        admin(patch(ADMIN + "/{id}/visibility", hidden), "{ \"visible\": false }").andExpect(status().isOk());
        assertThat(rankingTitles()).as("숨긴 상품은 바로 빠지고, 다시 읽어 직접 고친 값도 보인다").containsExactly("직접 고침");
    }

    private java.util.List<String> rankingTitles() throws Exception {
        return data(mockMvc.perform(get("/api/v1/products/ranking")).andExpect(status().isOk())).get("items").findValues("title").stream()
                .map(JsonNode::asString).toList();
    }

    @Test
    @DisplayName("메인 배너는 읽기 캐시에 둔다 — DB 를 직접 고치면 옛 목록, 관리자가 배너를 만들면(세대) 다음 조회부터 새 목록")
    void bannersAreCachedAndDroppedOnAdminWrite() throws Exception {
        jdbcTemplate.update("DELETE FROM banners");
        UUID product = registerInStock("배너 대상");
        String body = """
                {"badge":"b","title":"%s","description":"d","tabLabel":"t","icon":"Smartphone","imageUrl":"https://img/banner.png","imageAlt":"a",
                 "target":{"type":"PRODUCT","id":"%s"},"visible":true}""";
        admin(post("/api/v1/admin/banners"), body.formatted("첫 배너", product)).andExpect(status().isCreated());
        assertThat(bannerTitles()).containsExactly("첫 배너");
        jdbcTemplate.update("UPDATE banners SET title = '직접 고침'");

        assertThat(bannerTitles()).as("캐시의 옛 목록 — 캐시가 켜져 있다").containsExactly("첫 배너");
        admin(post("/api/v1/admin/banners"), body.formatted("둘째 배너", product)).andExpect(status().isCreated());
        assertThat(bannerTitles()).as("세대가 올라 다시 읽었다").containsExactly("직접 고침", "둘째 배너");
    }

    private java.util.List<String> bannerTitles() throws Exception {
        return data(mockMvc.perform(get("/api/v1/banners")).andExpect(status().isOk())).get("items").findValues("title").stream()
                .map(JsonNode::asString).toList();
    }

    @Test
    @DisplayName("사전예약 → 일반 전환도 세대를 올린다 — 캐시된 마감 상세가 다음 조회부터 일반 상품")
    void conversionInvalidates() throws Exception {
        UUID productId = fixtures.product(categoryId, "PREORDER", "ACTIVE", "전환", "t" + ShopFixtures.unique());
        fixtures.registration(productId);
        UUID optionId = fixtures.option(productId, "ACTIVE");
        Instant now = Instant.now();
        fixtures.campaign(productId, now.minus(Duration.ofHours(2)), now.minus(Duration.ofHours(1)));
        assertThat(data(mockMvc.perform(get("/api/v1/products/{id}", productId)).andExpect(status().isOk())).get("saleMode").asString())
                .isEqualTo("PREORDER");

        admin(post(ADMIN + "/{id}/conversion", productId), "{ \"stocks\": [ { \"variantId\": \"%s\", \"stock\": 1 } ] }".formatted(optionId))
                .andExpect(status().isOk());
        fixtures.stockReady(productId);   // order 가 전환 이벤트로 재고 행을 만든 것

        assertThat(data(mockMvc.perform(get("/api/v1/products/{id}", productId)).andExpect(status().isOk())).get("saleMode").asString())
                .isEqualTo("IN_STOCK");
    }

    @Test
    @DisplayName("리뷰를 지우면(API) 리뷰 세대가 올라 캐시된 상품 리뷰 목록에서 다음 조회부터 빠진다")
    void reviewDeleteInvalidates() throws Exception {
        UUID productId = registerInStock("리뷰 삭제");
        UUID customerId = UUID.randomUUID();
        reviews.saveAndFlush(ProductReview.write(productId, customerId, UUID.randomUUID(), 5, "지울 리뷰", "기본", "김**"));
        JsonNode listed = data(mockMvc.perform(get("/api/v1/products/{id}/reviews", productId))).get("items");
        assertThat(listed).hasSize(1);
        UUID reviewId = UUID.fromString(listed.get(0).get("reviewId").asString());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/reviews/{id}", reviewId)
                .with(com.grandis.nova.catalog.support.AccessTokens.customer(customerId))).andExpect(status().isNoContent());

        assertThat(data(mockMvc.perform(get("/api/v1/products/{id}/reviews", productId))).get("items")).isEmpty();
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
    @DisplayName("사전예약 접수 단계는 꺼낼 때 지금 시각으로 — 캐시된 상세는 오픈 시각이, 캐시된 목록은 마감 시각이 지나면 바로 바뀐다")
    void preorderStatusIsRecomputed() throws Exception {
        String tag = "p" + ShopFixtures.unique();
        UUID productId = fixtures.product(categoryId, "PREORDER", "ACTIVE", "오픈 직전", tag);
        fixtures.registration(productId);
        fixtures.option(productId, "ACTIVE");
        Instant opensAt = Instant.now().plusMillis(1500);
        fixtures.campaign(productId, opensAt, opensAt.plus(Duration.ofHours(1)));
        assertThat(data(mockMvc.perform(get("/api/v1/products/{id}", productId))).get("campaign").get("status").asString()).isEqualTo("BEFORE_OPEN");

        // 목록은 접수 중일 때만 싣는다 — 마감이 곧 오는 다른 상품으로 캐시된 목록의 단계 재계산을 본다
        UUID closing = fixtures.product(categoryId, "PREORDER", "ACTIVE", "마감 직전", tag);
        fixtures.registration(closing);
        fixtures.option(closing, "ACTIVE");
        Instant closesAt = Instant.now().plusMillis(1500);
        fixtures.campaign(closing, Instant.now().minus(Duration.ofHours(1)), closesAt);
        assertThat(list(tag).get(0).get("preorderStatus").asString()).isEqualTo("OPEN");

        // 캐시에서 꺼낸 것인지 확인하려고 DB 를 직접 고친다(세대 안 올림)
        jdbcTemplate.update("UPDATE products SET title = '직접 고침' WHERE id IN (?, ?)", UuidBinary.toBytes(productId), UuidBinary.toBytes(closing));
        Instant later = opensAt.isAfter(closesAt) ? opensAt : closesAt;
        Thread.sleep(Math.max(0, Duration.between(Instant.now(), later).toMillis()) + 200);

        JsonNode detail = data(mockMvc.perform(get("/api/v1/products/{id}", productId)));
        assertThat(detail.get("title").asString()).isEqualTo("오픈 직전");
        assertThat(detail.get("campaign").get("status").asString()).isEqualTo("OPEN");
        JsonNode cached = list(tag);
        assertThat(cached).as("캐시된 목록 그대로 — 오픈한 상품이 들어오는 것도, 마감한 상품이 빠지는 것도 TTL 만큼 늦다").hasSize(1);
        JsonNode item = cached.get(0);
        assertThat(item.get("title").asString()).isEqualTo("마감 직전");
        assertThat(item.get("preorderStatus").asString()).isEqualTo("CLOSED");
    }

    @Test
    @DisplayName("다른 서비스가 바꾼 것(판매 준비 완료)은 TTL 이 지나야 보인다 — 상품 목록 · 상세 TTL 5초")
    void crossServiceChangesWaitForTtl() throws Exception {
        String tag = "t" + ShopFixtures.unique();
        registerInStock("이미 준비", tag);   // 빈 목록은 넣지 않으므로 캐시될 목록을 먼저 만든다
        UUID productId = fixtures.product(categoryId, "IN_STOCK", "ACTIVE", "준비 전", tag);
        fixtures.registration(productId);
        fixtures.option(productId, "ACTIVE");
        assertThat(titles(tag)).containsExactly("이미 준비");

        fixtures.stockReady(productId);   // order 가 재고 행을 만든 것 — catalog 는 모른다
        assertThat(titles(tag)).as("TTL 안에서는 캐시된 목록").containsExactly("이미 준비");

        Thread.sleep(CatalogReadCache.PRODUCT_TTL.toMillis() + 300);
        assertThat(titles(tag)).containsExactlyInAnyOrder("이미 준비", "준비 전");
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

    @Test
    @DisplayName("상품 목록은 인스턴스 메모리에서 — DB 를 직접 고쳐도(세대 안 올림) 옛 값이고, Redis 가 멈춰도 그대로다. 대조군: 상세는 Redis 가 멈추면 DB 값")
    void productListIsServedFromMemory() throws Exception {
        UUID productId = registerInStock("메모리");
        mockMvc.perform(get("/api/v1/products/{id}", productId)).andExpect(status().isOk());
        assertThat(list("").findValues("title").stream().map(JsonNode::asString).toList()).contains("메모리");
        jdbcTemplate.update("UPDATE products SET title = '직접 고침' WHERE id = ?", (Object) UuidBinary.toBytes(productId));

        assertThat(list("").findValues("title").stream().map(JsonNode::asString).toList()).as("메모리의 옛 값").contains("메모리");
        RedisContainerConfig.pause();
        try {
            assertThat(list("").findValues("title").stream().map(JsonNode::asString).toList()).as("Redis 와 상관없다").contains("메모리");
            assertThat(data(mockMvc.perform(get("/api/v1/products/{id}", productId)).andExpect(status().isOk())).get("title").asString())
                    .as("대조군 — 상세는 Redis 캐시라 DB 로 답한다").isEqualTo("직접 고침");
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

    /** 색상 축(화이트) 하나인 일반 상품을 관리자 API 로 등록하고 판매 준비를 넣는다. */
    private UUID registerWithColor(String title) throws Exception {
        String body = """
                { "categoryId": "%s", "saleMode": "IN_STOCK", "title": "%s", "brand": "APPLE", "modelName": "A0001", "tags": "t%s", "visible": true,
                  "basePrice": 1000000,
                  "optionAxes": [ { "key": "color", "label": "색상", "values": [ { "value": "화이트" } ] } ],
                  "combinations": [ { "selections": { "color": "화이트" }, "stock": 3 } ],
                  "images": { "gallery": [ { "color": "화이트", "items": [ { "url": "https://img/w.jpg" } ] } ] } }
                """.formatted(categoryId, title, ShopFixtures.unique());
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

    private java.util.List<String> titlesOfPromotions() throws Exception {
        java.util.List<String> names = new java.util.ArrayList<>();
        data(mockMvc.perform(get("/api/v1/promotions")).andExpect(status().isOk())).get("items").forEach(item -> names.add(item.get("name").asString()));
        return names;
    }

    /**
     * 지금 세대 — 없으면 0 으로 심는다(없는 세대는 첫 조회 · 올리기가 지금 시각으로 만들어, 앞뒤를 비교하는 시험이 순서에 따라 갈리지 않게).
     * 심기는 쓰기다 — 컨텍스트의 캐시 빈은 앞 시험에서 지금 시각만 한 번호를 봤을 수 있어, 심은 뒤 공개 조회를 끼우면 복원으로 보고 올린다.
     * 그래서 앞뒤 사이에 공개 조회가 없는 시험에서만 쓴다.
     */
    private long generation(String name) {
        String key = "catalog:cache:gen:" + name;
        redis.opsForValue().setIfAbsent(key, "0");
        return Long.parseLong(redis.opsForValue().get(key));
    }

    private static String body(ResultActions actions) throws Exception {
        return actions.andReturn().getResponse().getContentAsString();
    }

    private static JsonNode data(ResultActions actions) throws Exception {
        return JSON.readTree(body(actions)).get("data");
    }
}
