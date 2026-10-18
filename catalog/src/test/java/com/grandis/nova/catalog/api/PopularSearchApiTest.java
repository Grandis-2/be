package com.grandis.nova.catalog.api;

import com.grandis.nova.catalog.ranking.ProductViewer;
import com.grandis.nova.catalog.ranking.SearchTerm;
import com.grandis.nova.catalog.ranking.SearchTermCounter;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
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
 * 검색 신호(POST /api/v1/products/searches)와 인기 검색어(GET /api/v1/products/searches/popular). Redis 는 시험 컨테이너, 읽기 캐시는 꺼져 있다.
 */
@CatalogIntegrationTest
@AutoConfigureMockMvc
class PopularSearchApiTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("yyyyMMddHH").withZone(ZoneOffset.UTC);

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired StringRedisTemplate redis;
    @Autowired SearchTermCounter counter;

    ShopFixtures fixtures;
    String tag;

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
        Set<String> keys = redis.keys("catalog:search:*");
        keys.addAll(redis.keys("catalog:signals:*"));
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
        tag = "s" + ShopFixtures.unique();
    }

    @Test
    @DisplayName("같은 사람 · 같은 검색어는 하루 한 번 — 대소문자 · 공백만 다른 표기는 한 검색어, 표기는 가장 최근에 센 것, 검색 수 순")
    void countsOncePerSearcherPerDayMergingVariants() throws Exception {
        listed("아이폰17 프로 " + tag, "아이폰 17");
        listed("Galaxy " + tag, "갤럭시");
        UUID anonymous = UUID.randomUUID();

        search("아이폰 17", anonymous).andExpect(status().isNoContent());
        search("아이폰17", anonymous).andExpect(status().isNoContent());
        search("galaxy", UUID.randomUUID()).andExpect(status().isNoContent());
        search("아이폰17", UUID.randomUUID()).andExpect(status().isNoContent());
        mockMvc.perform(json(post("/api/v1/products/searches"), "{\"q\":\"  아이폰   17 \"}").with(AccessTokens.customer(UUID.randomUUID())))
                .andExpect(status().isNoContent());
        search("GALAXY", UUID.randomUUID()).andExpect(status().isNoContent());

        assertThat(score("아이폰17")).as("익명 둘(같은 익명 id 는 한 번) + 회원 하나").isEqualTo(3);
        assertThat(score("galaxy")).isEqualTo(2);
        assertThat(popular()).containsExactly("아이폰 17", "GALAXY");
    }

    @Test
    @DisplayName("결과가 0건인 검색어와 금칙어는 세지 않는다 — 그래도 204")
    void zeroResultAndBannedAreNotCounted() throws Exception {
        listed("시발점 " + tag, "x");

        search("없는상품" + tag, UUID.randomUUID()).andExpect(status().isNoContent());
        search("시발점", UUID.randomUUID()).andExpect(status().isNoContent());
        search(tag, UUID.randomUUID()).andExpect(status().isNoContent());

        assertThat(score(("없는상품" + tag).toLowerCase())).isZero();
        assertThat(score("시발점")).as("결과가 있어도 금칙어").isZero();
        assertThat(popular()).as("대조군 — 결과 있는 검색어는 센다").containsExactly(tag);
    }

    @Test
    @DisplayName("최근 24시간 — 지금 시간을 포함한 시간 표 25개(24~25시간)를 합친다. 25시간 전 시간 표는 빠지고, 앞 10개까지")
    void windowIsLast24HourlyTablesTopTen() throws Exception {
        Instant hour = Instant.now().truncatedTo(ChronoUnit.HOURS);
        seed("old", hour.minus(Duration.ofHours(25)), 1000);
        seed("edge", hour.minus(Duration.ofHours(24)), 500);
        List<String> expected = new ArrayList<>(List.of("edge"));
        for (int i = 0; i < 10; i++) {
            seed("k" + i, hour, 100 - i);
            expected.add("k" + i);
        }

        assertThat(popular()).containsExactlyElementsOf(expected.subList(0, 10));
    }

    @Test
    @DisplayName("날짜는 KST 로 가른다 — KST 하루 안의 같은 사람 · 같은 검색어는 한 번, KST 자정이 지나면 다시 센다")
    void daysAreKoreanDates() {
        SearchTerm term = SearchTerm.of("Nova");
        ProductViewer searcher = ProductViewer.anonymous(UUID.randomUUID(), "198.51.100.1");

        assertThat(counter.record(term, searcher, Instant.parse("2026-10-18T15:00:00Z"))).as("KST 19일 00:00").isTrue();
        assertThat(counter.record(term, searcher, Instant.parse("2026-10-19T14:59:59Z"))).as("KST 19일 23:59:59 — 같은 날").isFalse();
        assertThat(counter.record(term, searcher, Instant.parse("2026-10-19T15:00:00Z"))).as("KST 20일 00:00 — 새 날").isTrue();
        assertThat(counter.top(Instant.parse("2026-10-19T15:30:00Z"), 10)).as("24~25시간 창 — 두 시간 표 모두").containsExactly("Nova");
        assertThat(counter.top(Instant.parse("2026-10-19T16:30:00Z"), 10)).as("한 시간 뒤 — 앞 시간 표는 창 밖, 그래도 남은 표의 1").containsExactly("Nova");
        assertThat(redis.opsForZSet().score("catalog:search:hour:2026101915", "nova")).isEqualTo(1);
        assertThat(redis.opsForZSet().score("catalog:search:hour:2026101815", "nova")).isEqualTo(1);
    }

    @Test
    @DisplayName("검색 신호 — 비거나 50자를 넘는 q 는 400, 비회원이 익명 id 없이 보내면 400, 관리자 토큰은 세지 않고 204")
    void signalRefusals() throws Exception {
        listed("Nova " + tag, "x");

        search("   ", UUID.randomUUID()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        search("가".repeat(51), UUID.randomUUID()).andExpect(status().isBadRequest());
        mockMvc.perform(json(post("/api/v1/products/searches"), "{\"q\":\"%s\"}".formatted(tag))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        mockMvc.perform(json(post("/api/v1/products/searches"), "{\"q\":\"%s\"}".formatted(tag)).with(AccessTokens.admin()))
                .andExpect(status().isNoContent());
        // 관리자 토큰이어도 q 는 검사한다
        mockMvc.perform(json(post("/api/v1/products/searches"), "{\"q\":\"  \"}").with(AccessTokens.admin())).andExpect(status().isBadRequest());

        assertThat(score(tag)).isZero();
    }

    @Test
    @DisplayName("Redis 가 멈추면 검색 신호는 204, 인기 검색어는 503")
    void redisDown() throws Exception {
        listed("Nova " + tag, "x");

        RedisContainerConfig.pause();
        try {
            search(tag, UUID.randomUUID()).andExpect(status().isNoContent());
            mockMvc.perform(get("/api/v1/products/searches/popular")).andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.error.code").value("DEPENDENCY_UNAVAILABLE"));
        } finally {
            RedisContainerConfig.unpause();
        }
        // 멈춘 동안 시간이 다 된 쓰기도 풀린 뒤 Redis 에 닿아 반영될 수 있다(조회수 시험과 같은 실측) — 그 신호를 셌는지는 단언하지 않는다
        double before = score(tag);
        search(tag, UUID.randomUUID()).andExpect(status().isNoContent());
        assertThat(score(tag)).as("대조군 — 풀리면 센다").isEqualTo(before + 1);
    }

    @Test
    @DisplayName("표기는 가장 최근 시간의 것 — 앞 시간에 더 많이 쓰인 표기가 있어도")
    void displayIsFromTheLatestHour() {
        Instant hour = Instant.now().truncatedTo(ChronoUnit.HOURS);
        redis.opsForZSet().incrementScore("catalog:search:hour:" + HOUR.format(hour.minus(Duration.ofHours(3))), "nova", 5);
        redis.opsForHash().put("catalog:search:display:" + HOUR.format(hour.minus(Duration.ofHours(3))), "nova", "NOVA");
        redis.opsForZSet().incrementScore("catalog:search:hour:" + HOUR.format(hour.minus(Duration.ofHours(1))), "nova", 1);
        redis.opsForHash().put("catalog:search:display:" + HOUR.format(hour.minus(Duration.ofHours(1))), "nova", "Nova");

        assertThat(counter.top(Instant.now(), 10)).containsExactly("Nova");
    }

    @Test
    @DisplayName("키는 만료가 걸린다 — 시간 표 · 그 시간 표기 26시간, 그날 센 사람 2일, IP 별 익명 id 2일")
    void keysExpire() throws Exception {
        listed("Nova " + tag, "x");
        search(tag, UUID.randomUUID()).andExpect(status().isNoContent());

        String hour = HOUR.format(Instant.now());
        String day = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).format(DateTimeFormatter.BASIC_ISO_DATE);
        assertThat(ttl("catalog:search:hour:" + hour)).isBetween(Duration.ofHours(26).minusMinutes(1).toSeconds(), Duration.ofHours(26).toSeconds());
        assertThat(ttl("catalog:search:display:" + hour)).isBetween(Duration.ofHours(26).minusMinutes(1).toSeconds(), Duration.ofHours(26).toSeconds());
        assertThat(ttl("catalog:search:seen:" + day + ":" + tag.toLowerCase())).isBetween(Duration.ofDays(2).minusMinutes(1).toSeconds(), Duration.ofDays(2).toSeconds());
        assertThat(ttl("catalog:signals:anon:" + day + ":127.0.0.1")).isBetween(Duration.ofDays(2).minusMinutes(1).toSeconds(), Duration.ofDays(2).toSeconds());
    }

    @Test
    @DisplayName("금칙어는 공백을 남긴 표기에서 찾는다 — \"갤럭시 발매일\" 은 센다, 결과가 있어도 \"시발\" 이 든 표기 · 붙여 쓴 \"갤럭시발매일\" 은 안 센다")
    void bannedWordsRespectWordBoundaries() throws Exception {
        listed("갤럭시 발매일 " + tag, "x");
        listed("시발 노트 " + tag, "갤럭시발매일" + tag);

        search("갤럭시 발매일 " + tag, UUID.randomUUID()).andExpect(status().isNoContent());
        search("시발 노트 " + tag, UUID.randomUUID()).andExpect(status().isNoContent());
        search("갤럭시발매일" + tag, UUID.randomUUID()).andExpect(status().isNoContent());
        search("노트 " + tag, UUID.randomUUID()).andExpect(status().isNoContent());

        assertThat(score(("갤럭시발매일" + tag).toLowerCase())).as("띄어 쓴 표기만 셌다").isEqualTo(1);
        assertThat(score(("시발노트" + tag).toLowerCase())).isZero();
        assertThat(score(("노트" + tag).toLowerCase())).as("대조군 — 같은 상품에 걸리는 다른 검색어는 센다").isEqualTo(1);
    }

    @Test
    @DisplayName("검색 신호도 IP 하나가 하루에 익명 id 20개까지 — 21번째 새 id 는 세지 않는다")
    void anonymousIdsPerIpAreCapped() throws Exception {
        listed("Nova " + tag, "x");
        for (int i = 0; i < 21; i++) {
            mockMvc.perform(json(post("/api/v1/products/searches").header("X-Forwarded-For", "203.0.113.9, 130.176.0.1"),
                    JSON.writeValueAsString(new PopularSearchController.SearchRequest(tag, UUID.randomUUID())))).andExpect(status().isNoContent());
        }

        assertThat(score(tag)).isEqualTo(20);
    }

    @Test
    @DisplayName("검색이 없으면 빈 목록")
    void emptyPopular() throws Exception {
        assertThat(popular()).isEmpty();
    }

    // ── 도우미 ──

    /** 목록에 나오는 일반 상품. */
    private void listed(String title, String tags) {
        UUID product = fixtures.product(fixtures.category(), "IN_STOCK", "ACTIVE", title, tags);
        fixtures.option(product, "ACTIVE", ShopFixtures.OPTION_PRICE);
        fixtures.stockReady(product);
    }

    private ResultActions search(String q, UUID anonymousId) throws Exception {
        return mockMvc.perform(json(post("/api/v1/products/searches"), JSON.writeValueAsString(new PopularSearchController.SearchRequest(q, anonymousId))));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private List<String> popular() throws Exception {
        String body = mockMvc.perform(get("/api/v1/products/searches/popular")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<String> keywords = new ArrayList<>();
        JSON.readTree(body).get("data").get("keywords").forEach(k -> keywords.add(k.asString()));
        return keywords;
    }

    private long ttl(String key) {
        Long seconds = redis.getExpire(key, java.util.concurrent.TimeUnit.SECONDS);
        return seconds == null ? -2 : seconds;
    }

    /** 지금 시간 표에서 그 합침 키의 수. */
    private double score(String key) {
        Double score = redis.opsForZSet().score("catalog:search:hour:" + HOUR.format(Instant.now()), key);
        return score == null ? 0 : score;
    }

    /** 그 시간 표에 직접 쓴다 — 여러 시간에 걸친 검색을 시험 안에서 만든다. 표기는 합침 키 그대로. */
    private void seed(String key, Instant hour, double count) {
        redis.opsForZSet().incrementScore("catalog:search:hour:" + HOUR.format(hour), key, count);
        redis.opsForHash().put("catalog:search:display:" + HOUR.format(hour), key, key);
    }
}
