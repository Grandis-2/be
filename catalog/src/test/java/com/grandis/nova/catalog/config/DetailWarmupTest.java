package com.grandis.nova.catalog.config;

import com.grandis.nova.catalog.support.AccessTokens;
import com.grandis.nova.catalog.support.ImageAttachmentsTestConfig;
import com.grandis.nova.catalog.support.MySqlContainerConfig;
import com.grandis.nova.catalog.support.PreorderCampaignsTestConfig;
import com.grandis.nova.catalog.support.RedisContainerConfig;
import com.grandis.nova.catalog.support.SecurityTestConfig;
import com.grandis.nova.catalog.support.ShopFixtures;
import com.grandis.nova.common.UuidBinary;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.filter.OncePerRequestFilter;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 기동 예열을 실제 Tomcat 으로 — 예열 중에는 readiness 가 닫혀 있어 ALB 가 보내지 않고, 홍보 상세 · 상품 상세를 실제 HTTP 로 부른다.
 * 홍보 · 상품은 예열보다 먼저 도는 러너가 DB 에 넣는다(컨텍스트가 뜨는 중이라 시험 메서드보다 앞서야 한다).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.hikari.transaction-isolation=TRANSACTION_READ_COMMITTED",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.open-in-view=false",
        "spring.jpa.properties.hibernate.jdbc.time_zone=UTC",
        "spring.flyway.locations=filesystem:${nova.migrations-path}",
        "spring.flyway.default-schema=shop",
        "spring.flyway.schemas=shop",
        "spring.flyway.create-schemas=false",
        "spring.flyway.clean-disabled=true",
        "spring.flyway.validate-migration-naming=true",
        "spring.threads.virtual.enabled=true",
        "jwt.issuer=" + AccessTokens.ISSUER,
        "spring.data.redis.timeout=300ms",
        "spring.data.redis.connect-timeout=200ms",
        "nova.outbox.transport=log",
        "nova.outbox.log-transport-allowed=true",
        "nova.outbox.relay-interval=1h",
        "nova.sqs.consumer.enabled=false",
        "nova.upload.bucket=catalog-test-images",
        "nova.upload.cdn-base-url=https://img",
        "nova.upload.region=ap-northeast-2",
        "management.server.port=0",
        "nova.read-cache.enabled=true",
        "nova.warmup.budget=10s"
})
@Import({MySqlContainerConfig.class, RedisContainerConfig.class, SecurityTestConfig.class, ImageAttachmentsTestConfig.class,
        PreorderCampaignsTestConfig.class, DetailWarmupTest.Seed.class})
class DetailWarmupTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Value("${local.management.port}") int managementPort;
    @Autowired DetailWarmup warmup;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired org.springframework.data.redis.core.StringRedisTemplate redis;

    @Test
    @DisplayName("기동 예열 중에는 readiness 가 503 — 끝난 뒤에야 200(ALB 가 예열이 끝난 인스턴스에만 보낸다)")
    void readinessStaysClosedWhileWarming() throws Exception {
        assertThat(Seed.READINESS_DURING_WARMUP).as("예열 요청을 받는 동안 본 readiness").containsOnly(503);
        assertThat(get(managementPort, "/actuator/health/readiness").statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("기동 때 오늘 보이는 홍보의 상세와 그 카드의 상품 상세를 실제 HTTP 로 부른다")
    void warmsPromotionAndProductDetailsAtStartup() {
        // 같은 DB 의 다른 시험이 남긴 홍보가 많으면 넣은 홍보는 홍보 상한(20) 밖일 수 있다 — 넣은 홍보는 아래 상한 시험이 본다
        assertThat(Seed.WARMED_AT_STARTUP).anyMatch(path -> path.matches("/api/v1/promotions/[0-9a-f-]{36}"));
        assertThat(Seed.WARMED_AT_STARTUP).anyMatch(path -> path.startsWith("/api/v1/products/"));
    }

    @Test
    @DisplayName("요청 수 상한까지 돌고, 다른 서비스를 묻는 요청(상품 상세)은 따로 정한 상한까지만 — 실패 없이")
    void stopsAtTheRequestCaps() throws Exception {
        DetailWarmup.Result result = withPromotionsHidden(List.of(Seed.promotionId), warmup::warm);

        assertThat(result.paths()).containsExactly("/api/v1/promotions/" + Seed.promotionId, "/api/v1/products/" + Seed.productId);
        assertThat(result.requests()).isEqualTo(DetailWarmup.MAX_REQUESTS);
        assertThat(result.dependentRequests()).isEqualTo(DetailWarmup.MAX_DEPENDENT_REQUESTS);
        assertThat(result.failed()).isZero();
    }

    @Test
    @DisplayName("홍보가 많아도 상세는 홍보 상한까지만 부른다")
    void promotionsAreCapped() throws Exception {
        List<UUID> many = new java.util.ArrayList<>();
        for (int i = 0; i < DetailWarmup.MAX_PROMOTIONS + 5; i++) {
            UUID product = fixtures().product(fixtures().category(), "PREORDER", "ACTIVE", "예열 많은 홍보 상품 " + i, "t");   // 한 상품은 한 홍보에만
            many.add(Seed.promotion(jdbcTemplate, product, "예열 많은 홍보 " + i));
        }

        DetailWarmup.Result result;
        try {
            result = withPromotionsHidden(many, warmup::warm);
        } finally {
            retire(many);
        }

        assertThat(result.paths().stream().filter(path -> path.startsWith("/api/v1/promotions/"))).hasSize(DetailWarmup.MAX_PROMOTIONS);
    }

    @Test
    @DisplayName("일반 상품 카드가 든 홍보 상세도 요청마다 order 재고를 묻는다 — 그 홍보도 상한에 세어 order 호출이 상한 + 대상 고르기를 넘지 않는다")
    void inStockPromotionCountsTowardTheDependentCap() throws Exception {
        UUID inStock = fixtures().product(fixtures().category(), "IN_STOCK", "ACTIVE", "예열 일반", "t");
        fixtures().registration(inStock);
        fixtures().option(inStock, "ACTIVE");
        fixtures().stockReady(inStock);
        UUID promotion = Seed.promotion(jdbcTemplate, inStock, "예열 일반 홍보");

        int before = Seed.STOCK_CALLS.get();
        DetailWarmup.Result result;
        try {
            result = withPromotionsHidden(List.of(promotion), warmup::warm);
        } finally {
            retire(List.of(promotion));
        }
        int stockCalls = Seed.STOCK_CALLS.get() - before;

        assertThat(result.paths()).containsExactly("/api/v1/promotions/" + promotion, "/api/v1/products/" + inStock);
        assertThat(result.requests()).as("모든 대상이 다른 서비스를 묻는다 — 대상을 고르며 읽은 홍보 상세 하나를 빼고 상한에서 멈춘다")
                .isEqualTo(DetailWarmup.MAX_DEPENDENT_REQUESTS - 1);
        assertThat(result.dependentRequests()).isEqualTo(DetailWarmup.MAX_DEPENDENT_REQUESTS);
        assertThat(stockCalls).as("대상 고르기를 포함해 상한까지").isBetween(1, DetailWarmup.MAX_DEPENDENT_REQUESTS);
    }

    @Test
    @DisplayName("오늘 보이는 홍보가 없으면 상품 목록 첫 쪽의 상세를 데운다 — 다른 서비스를 묻는 요청 상한에서 바로 멈춘다(시간 상한까지 헛돌지 않는다)")
    void fallsBackToTheProductList() throws Exception {
        long started = System.nanoTime();
        DetailWarmup.Result result = withPromotionsHidden(List.of(), warmup::warm);

        assertThat(result.paths()).isNotEmpty().allMatch(path -> path.startsWith("/api/v1/products/"));
        assertThat(result.requests()).as("상품 목록 조회 하나를 빼고 상한까지").isEqualTo(DetailWarmup.MAX_DEPENDENT_REQUESTS - 1);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(DetailWarmup.DEFAULT_BUDGET.dividedBy(2));
    }

    @Test
    @DisplayName("응답이 늦어도 시간 상한을 넘기지 않는다 — 요청마다 응답을 기다리는 시간을 남은 시간으로 줄인다")
    void stopsAtTheBudgetEvenWhenResponsesStall(@Autowired org.springframework.context.ApplicationContext context,
                                                @Autowired tools.jackson.databind.json.JsonMapper json) {
        DetailWarmup quick = new DetailWarmup(context, json, Duration.ofMillis(300));
        Seed.STALL.set(true);   // 예열 요청은 응답 전에 2초 멈춘다 — 요청 제한 시간(3초)을 그대로 쓰면 2초 넘게 걸린다
        long started = System.nanoTime();
        try {
            quick.warm();
        } finally {
            Seed.STALL.set(false);
        }

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
    }

    @Test
    @DisplayName("끈 예열(0 이하)은 아무것도 부르지 않는다")
    void zeroBudgetDoesNothing(@Autowired org.springframework.context.ApplicationContext context, @Autowired tools.jackson.databind.json.JsonMapper json) {
        assertThat(new DetailWarmup(context, json, Duration.ZERO).warm()).isEqualTo(DetailWarmup.Result.NONE);
    }

    /** 이 시험이 넣은 홍보를 지난 기간으로 — 같은 DB 를 쓰는 다른 시험의 홍보 목록에 남지 않게. */
    private void retire(List<UUID> promotions) {
        for (UUID id : promotions) {
            jdbcTemplate.update("UPDATE promotions SET start_date = CURDATE() - INTERVAL 4 DAY, end_date = CURDATE() - INTERVAL 3 DAY WHERE id = ?",
                    (Object) UuidBinary.toBytes(id));
        }
        clearCachedValues();
    }

    private ShopFixtures fixtures() {
        return new ShopFixtures(jdbcTemplate);
    }

    /** keep 의 홍보만 오늘 보이게 두고 부른다 — 같은 DB 를 쓰는 다른 시험이 남긴 홍보는 끝나면 되돌린다. */
    private <T> T withPromotionsHidden(List<UUID> keep, java.util.concurrent.Callable<T> call) throws Exception {
        List<java.util.Map<String, Object>> saved = jdbcTemplate.queryForList("SELECT id, start_date, end_date FROM promotions");
        try {
            for (java.util.Map<String, Object> row : saved) {
                if (!keep.contains(UuidBinary.fromBytes((byte[]) row.get("id")))) {
                    jdbcTemplate.update("UPDATE promotions SET start_date = CURDATE() - INTERVAL 4 DAY, end_date = CURDATE() - INTERVAL 3 DAY WHERE id = ?",
                            row.get("id"));
                }
            }
            clearCachedValues();   // 홍보 목록 캐시를 비운다 — 세대는 올리지 않고 값만
            return call.call();
        } finally {
            for (java.util.Map<String, Object> row : saved) {
                jdbcTemplate.update("UPDATE promotions SET start_date = ?, end_date = ? WHERE id = ?", row.get("start_date"), row.get("end_date"), row.get("id"));
            }
            clearCachedValues();
        }
    }

    private void clearCachedValues() {
        java.util.Set<String> keys = redis.keys("catalog:cache:*");
        keys.removeIf(key -> key.contains(":gen:"));
        if (!keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    /** 기동 때 넣은 홍보도 끝나면 지난 기간으로 — 같은 DB 를 쓰는 다른 시험 클래스의 홍보 목록에 남지 않게. */
    @org.junit.jupiter.api.AfterAll
    static void retireSeededPromotion(@Autowired JdbcTemplate jdbcTemplate, @Autowired org.springframework.data.redis.core.StringRedisTemplate redis) {
        jdbcTemplate.update("UPDATE promotions SET start_date = CURDATE() - INTERVAL 4 DAY, end_date = CURDATE() - INTERVAL 3 DAY WHERE id = ?",
                (Object) UuidBinary.toBytes(Seed.promotionId));
        // 예열이 채운 홍보 목록 캐시도 비운다 — 같은 Redis 를 쓰는 다음 시험이 지난 홍보를 TTL 동안 받지 않게(세대 키는 둔다)
        java.util.Set<String> keys = redis.keys("catalog:cache:*");
        keys.removeIf(key -> key.contains(":gen:"));
        if (!keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    private static HttpResponse<String> get(int port, String path) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    /** 예열보다 먼저 홍보 하나(사전예약 상품 하나)를 넣고, 예열 요청을 지켜본다. */
    @TestConfiguration(proxyBeanMethods = false)
    static class Seed {

        static final List<String> WARMED = new CopyOnWriteArrayList<>();
        static final java.util.concurrent.atomic.AtomicBoolean STALL = new java.util.concurrent.atomic.AtomicBoolean();
        static final AtomicInteger STOCK_CALLS = new AtomicInteger();
        static final List<Integer> READINESS_DURING_WARMUP = new CopyOnWriteArrayList<>();
        /** 기동 예열이 부른 경로 — ApplicationReadyEvent(러너가 끝난 뒤) 때 떠 둔다. 시험 메서드의 warm() 은 섞이지 않는다. */
        static final List<String> WARMED_AT_STARTUP = new CopyOnWriteArrayList<>();
        static UUID promotionId;
        static UUID productId;

        @Bean
        @Order(Ordered.HIGHEST_PRECEDENCE)
        ApplicationRunner seedPromotion(JdbcTemplate jdbcTemplate) {
            return args -> {
                ShopFixtures fixtures = new ShopFixtures(jdbcTemplate);
                productId = fixtures.product(fixtures.category(), "PREORDER", "ACTIVE", "예열 상품", "t");
                fixtures.registration(productId);
                fixtures.option(productId, "ACTIVE");
                Instant now = Instant.now();
                fixtures.campaign(productId, now.minus(Duration.ofHours(1)), now.plus(Duration.ofHours(1)));
                promotionId = promotion(jdbcTemplate, productId, "예열 홍보");
            };
        }

        /** 오늘 보이는 홍보 하나(상품 하나) — 관리자 API 는 업로드된 사진 행이 필요해 행으로 넣는다. */
        static UUID promotion(JdbcTemplate jdbcTemplate, UUID productId, String name) {
            UUID id = UUID.randomUUID();
            jdbcTemplate.update("""
                    INSERT INTO promotions (id, idempotency_key, name, start_date, end_date, thumbnail_url, detail_image_urls, created_at, updated_at)
                    VALUES (?, ?, ?, CURDATE() - INTERVAL 1 DAY, CURDATE() + INTERVAL 1 DAY, 'https://img/t.jpg', JSON_ARRAY(), NOW(6), NOW(6))
                    """, UuidBinary.toBytes(id), "k-" + ShopFixtures.unique(), name);
            jdbcTemplate.update("INSERT INTO promotion_products (promotion_id, position, product_id) VALUES (?, 0, ?)",
                    UuidBinary.toBytes(id), UuidBinary.toBytes(productId));
            return id;
        }

        /** order 재고 자리 — 부른 수만 센다(재고 행은 보지 않는다 — 품절로 보여도 예열에는 같다). */
        @Bean
        @org.springframework.context.annotation.Primary
        com.grandis.nova.catalog.integration.StockClient countingStockClient() {
            return optionIds -> {
                STOCK_CALLS.incrementAndGet();
                return com.grandis.nova.common.web.ApiResponse.ok(new com.grandis.nova.catalog.integration.StockClient.Stocks(List.of()));
            };
        }

        @Bean
        org.springframework.context.ApplicationListener<org.springframework.boot.context.event.ApplicationReadyEvent> snapshotStartupWarmup() {
            return event -> WARMED_AT_STARTUP.addAll(WARMED);
        }

        @Bean
        OncePerRequestFilter watchWarmup(Environment environment) {
            AtomicInteger seen = new AtomicInteger();
            return new OncePerRequestFilter() {
                @Override
                protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws java.io.IOException,
                        jakarta.servlet.ServletException {
                    if (DetailWarmup.USER_AGENT.equals(request.getHeader("User-Agent"))) {
                        WARMED.add(request.getRequestURI());
                        if (STALL.get()) {
                            try {
                                Thread.sleep(2_000);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }
                        if (seen.getAndIncrement() < 3) {   // 처음 몇 번만 — 관리 포트에 묻는 비용을 예열에 얹지 않게
                            try {
                                READINESS_DURING_WARMUP.add(get(environment.getRequiredProperty("local.management.port", Integer.class),
                                        "/actuator/health/readiness").statusCode());
                            } catch (Exception e) {
                                READINESS_DURING_WARMUP.add(-1);
                            }
                        }
                    }
                    chain.doFilter(request, response);
                }
            };
        }
    }
}
