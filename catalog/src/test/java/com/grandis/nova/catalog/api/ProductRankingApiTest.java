package com.grandis.nova.catalog.api;

import com.grandis.nova.catalog.ranking.ProductViewCounter;
import com.grandis.nova.catalog.ranking.ProductViewer;
import com.grandis.nova.catalog.support.AccessTokens;
import com.grandis.nova.catalog.support.CatalogIntegrationTest;
import com.grandis.nova.catalog.support.RedisContainerConfig;
import com.grandis.nova.catalog.support.ShopFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 조회 신호(POST /api/v1/products/{id}/views)와 조회수 랭킹(GET /api/v1/products/ranking). Redis 는 시험 컨테이너, 읽기 캐시는 꺼져 있다.
 */
@CatalogIntegrationTest
@AutoConfigureMockMvc
class ProductRankingApiTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final ZoneId KOREA = ZoneId.of("Asia/Seoul");

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired StringRedisTemplate redis;
    @Autowired ProductViewCounter counter;

    ShopFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
        Set<String> keys = redis.keys("catalog:views:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    @Test
    @DisplayName("같은 사람 · 같은 상품은 하루 한 번 — 익명 id 와 회원은 각각 한 사람, 조회수 순으로 랭킹(목록과 같은 카드)")
    void countsOncePerViewerPerDay() throws Exception {
        UUID popular = listed();
        UUID second = listed();
        UUID anonymous = UUID.randomUUID();
        UUID customer = UUID.randomUUID();

        view(popular, anonymous).andExpect(status().isNoContent());
        view(popular, anonymous).andExpect(status().isNoContent());
        view(popular, anonymous).andExpect(status().isNoContent());
        view(popular, UUID.randomUUID()).andExpect(status().isNoContent());
        mockMvc.perform(post("/api/v1/products/{id}/views", second).with(AccessTokens.customer(customer))).andExpect(status().isNoContent());
        mockMvc.perform(post("/api/v1/products/{id}/views", second).with(AccessTokens.customer(customer))).andExpect(status().isNoContent());

        assertThat(score(popular, 0)).as("익명 둘 — 같은 익명 id 는 한 번").isEqualTo(2);
        assertThat(score(second, 0)).as("회원 하나 — 본문 없이 회원으로 센다").isEqualTo(1);
        JsonNode items = ranking();
        assertThat(ids(items)).containsExactly(popular.toString(), second.toString());
        assertThat(items.get(0).get("title").asString()).isEqualTo("Nova 1");
        assertThat(items.get(0).get("minPrice").decimalValue()).isEqualByComparingTo(ShopFixtures.OPTION_PRICE);
    }

    @Test
    @DisplayName("랭킹은 오늘을 포함한 최근 3일(KST 날짜)을 합친다 — 3일 전 조회수는 빠진다")
    void windowIsLastThreeKoreanDays() throws Exception {
        UUID today = listed();
        UUID twoDaysAgo = listed();
        UUID threeDaysAgo = listed();
        seed(today, 0, 3);
        seed(twoDaysAgo, 2, 2);
        seed(today, 2, 1);
        seed(threeDaysAgo, 3, 100);

        assertThat(ids(ranking())).containsExactly(today.toString(), twoDaysAgo.toString());
    }

    @Test
    @DisplayName("목록에 나오는 상품만 — 숨김 · 판매 중지 · 접수 전 사전예약은 빠지고, 접수 중인 사전예약은 오른다. 앞 10개까지")
    void onlyListedProductsTopTen() throws Exception {
        UUID hidden = listed();
        UUID paused = listed();
        UUID openPreorder = fixtures.product("PREORDER", "ACTIVE");
        fixtures.option(openPreorder, "ACTIVE", ShopFixtures.OPTION_PRICE);
        fixtures.campaign(openPreorder, Instant.now().minus(Duration.ofHours(1)), Instant.now().plus(Duration.ofDays(1)));
        UUID upcoming = fixtures.product("PREORDER", "ACTIVE");
        fixtures.option(upcoming, "ACTIVE", ShopFixtures.OPTION_PRICE);
        fixtures.campaign(upcoming, Instant.now().plus(Duration.ofDays(1)), Instant.now().plus(Duration.ofDays(2)));
        jdbcTemplate.update("UPDATE products SET visible = 0 WHERE id = UUID_TO_BIN(?)", hidden.toString());
        jdbcTemplate.update("UPDATE products SET status = 'PAUSED' WHERE id = UUID_TO_BIN(?)", paused.toString());
        seed(hidden, 0, 1000);
        seed(paused, 0, 900);
        seed(upcoming, 0, 800);
        seed(openPreorder, 0, 700);
        List<UUID> rest = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            UUID product = listed();
            seed(product, 0, 100 - i);
            rest.add(product);
        }

        List<String> ranked = ids(ranking());

        List<String> expected = new ArrayList<>();
        expected.add(openPreorder.toString());
        rest.subList(0, 9).forEach(id -> expected.add(id.toString()));
        assertThat(ranked).containsExactlyElementsOf(expected);
    }

    @Test
    @DisplayName("조회 신호 — 없거나 숨긴 상품 404, 비회원이 익명 id 없이 보내면 400, 관리자 토큰은 세지 않고 204")
    void signalRefusals() throws Exception {
        UUID product = listed();
        UUID hidden = listed();
        jdbcTemplate.update("UPDATE products SET visible = 0 WHERE id = UUID_TO_BIN(?)", hidden.toString());

        view(UUID.randomUUID(), UUID.randomUUID()).andExpect(status().isNotFound());
        view(hidden, UUID.randomUUID()).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/products/{id}/views", product)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        mockMvc.perform(post("/api/v1/products/{id}/views", product).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/products/{id}/views", product).with(AccessTokens.admin())).andExpect(status().isNoContent());

        assertThat(score(product, 0)).isZero();
        assertThat(score(hidden, 0)).isZero();
    }

    @Test
    @DisplayName("Redis 가 멈추면 조회 신호는 204(상세 화면을 막지 않는다), 랭킹은 503")
    void redisDown() throws Exception {
        UUID product = listed();

        RedisContainerConfig.pause();
        try {
            view(product, UUID.randomUUID()).andExpect(status().isNoContent());
            mockMvc.perform(get("/api/v1/products/ranking")).andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.error.code").value("DEPENDENCY_UNAVAILABLE"));
        } finally {
            RedisContainerConfig.unpause();
        }
        // 멈춘 동안 시간이 다 된 쓰기도 풀린 뒤 Redis 에 닿아 반영될 수 있다(실측) — 그 신호를 셌는지는 단언하지 않는다
        double before = score(product, 0);
        view(product, UUID.randomUUID()).andExpect(status().isNoContent());
        assertThat(score(product, 0)).as("대조군 — 풀리면 센다").isEqualTo(before + 1);
    }

    @Test
    @DisplayName("날짜는 KST 로 가른다 — UTC 16:00(KST 다음 날 01:00)의 조회는 KST 날짜 표에, 같은 사람이 KST 하루 안에 다시 보면 안 센다")
    void daysAreKoreanDates() {
        UUID product = UUID.randomUUID();
        ProductViewer viewer = ProductViewer.anonymous(UUID.randomUUID());
        Instant kstNextDay = Instant.parse("2026-10-18T16:00:00Z");

        assertThat(counter.record(product, viewer, kstNextDay)).isTrue();
        assertThat(counter.record(product, viewer, Instant.parse("2026-10-19T14:59:59Z"))).as("KST 19일 23:59:59 — 같은 날").isFalse();
        assertThat(counter.record(product, viewer, Instant.parse("2026-10-19T15:00:00Z"))).as("KST 20일 00:00 — 새 날").isTrue();

        assertThat(redis.opsForZSet().score("catalog:views:day:20261019", product.toString())).isEqualTo(1);
        assertThat(redis.opsForZSet().score("catalog:views:day:20261020", product.toString())).isEqualTo(1);
        assertThat(redis.opsForZSet().score("catalog:views:day:20261018", product.toString())).as("UTC 날짜 표에는 없다").isNull();
        assertThat(counter.top(Instant.parse("2026-10-21T15:00:00Z"), 10)).as("KST 22일 — 20 · 21 · 22일만 합친다").containsExactly(product);
        assertThat(counter.top(Instant.parse("2026-10-22T15:00:00Z"), 10)).as("KST 23일 — 20일도 창 밖").isEmpty();
    }

    @Test
    @DisplayName("조회가 없으면 빈 랭킹")
    void emptyRanking() throws Exception {
        assertThat(ranking()).isEmpty();
    }

    // ── 도우미 ──

    /** 목록에 나오는 일반 상품(공개 · 판매 중 · 재고 행 있음). */
    private UUID listed() {
        UUID product = fixtures.product("IN_STOCK", "ACTIVE");
        fixtures.option(product, "ACTIVE", ShopFixtures.OPTION_PRICE);
        fixtures.stockReady(product);
        return product;
    }

    private ResultActions view(UUID product, UUID anonymousId) throws Exception {
        return mockMvc.perform(post("/api/v1/products/{id}/views", product).contentType(MediaType.APPLICATION_JSON)
                .content("{\"anonymousId\":\"%s\"}".formatted(anonymousId)));
    }

    private JsonNode ranking() throws Exception {
        String body = mockMvc.perform(get("/api/v1/products/ranking")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JSON.readTree(body).get("data").get("items");
    }

    private static List<String> ids(JsonNode items) {
        List<String> ids = new ArrayList<>();
        items.forEach(item -> ids.add(item.get("productId").asString()));
        return ids;
    }

    /** 그날(오늘에서 daysAgo 전, KST) 조회수 표에 직접 쓴다 — 여러 날에 걸친 조회를 시험 안에서 만든다. */
    private void seed(UUID product, int daysAgo, double views) {
        redis.opsForZSet().incrementScore(dayKey(daysAgo), product.toString(), views);
    }

    private double score(UUID product, int daysAgo) {
        Double score = redis.opsForZSet().score(dayKey(daysAgo), product.toString());
        return score == null ? 0 : score;
    }

    private static String dayKey(int daysAgo) {
        return "catalog:views:day:" + LocalDate.now(KOREA).minusDays(daysAgo).format(DateTimeFormatter.BASIC_ISO_DATE);
    }
}
