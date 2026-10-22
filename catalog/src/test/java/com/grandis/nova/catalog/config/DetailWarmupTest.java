package com.grandis.nova.catalog.config;

import com.grandis.nova.catalog.support.AccessTokens;
import com.grandis.nova.catalog.support.ImageAttachmentsTestConfig;
import com.grandis.nova.catalog.support.MySqlContainerConfig;
import com.grandis.nova.catalog.support.OrderStocksTestConfig;
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
        OrderStocksTestConfig.class, PreorderCampaignsTestConfig.class, DetailWarmupTest.Seed.class})
class DetailWarmupTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Value("${local.management.port}") int managementPort;
    @Autowired DetailWarmup warmup;

    @Test
    @DisplayName("기동 예열 중에는 readiness 가 503 — 끝난 뒤에야 200(ALB 가 예열이 끝난 인스턴스에만 보낸다)")
    void readinessStaysClosedWhileWarming() throws Exception {
        assertThat(Seed.READINESS_DURING_WARMUP).as("예열 요청을 받는 동안 본 readiness").containsOnly(503);
        assertThat(get(managementPort, "/actuator/health/readiness").statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("오늘 보이는 홍보의 상세와 그 카드의 상품 상세를 실제 HTTP 로 부른다 — 요청 수 상한까지, 실패 없이")
    void warmsPromotionAndProductDetails() {
        assertThat(Seed.WARMED).contains("/api/v1/promotions/" + Seed.promotionId, "/api/v1/products/" + Seed.productId);

        DetailWarmup.Result again = warmup.warm();
        // 같은 DB 를 쓰는 다른 시험이 남긴 홍보도 대상이 될 수 있다 — 넣은 것이 들어 있는지만 본다
        assertThat(again.paths()).contains("/api/v1/promotions/" + Seed.promotionId, "/api/v1/products/" + Seed.productId);
        assertThat(again.requests()).isEqualTo(DetailWarmup.MAX_REQUESTS);
        assertThat(again.failed()).isZero();
    }

    private static HttpResponse<String> get(int port, String path) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    /** 예열보다 먼저 홍보 하나(사전예약 상품 하나)를 넣고, 예열 요청을 지켜본다. */
    @TestConfiguration(proxyBeanMethods = false)
    static class Seed {

        static final List<String> WARMED = new CopyOnWriteArrayList<>();
        static final List<Integer> READINESS_DURING_WARMUP = new CopyOnWriteArrayList<>();
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
                promotionId = UUID.randomUUID();
                jdbcTemplate.update("""
                        INSERT INTO promotions (id, idempotency_key, name, start_date, end_date, thumbnail_url, detail_image_urls, created_at, updated_at)
                        VALUES (?, ?, '예열 홍보', CURDATE() - INTERVAL 1 DAY, CURDATE() + INTERVAL 1 DAY, 'https://img/t.jpg', JSON_ARRAY(), NOW(6), NOW(6))
                        """, UuidBinary.toBytes(promotionId), "k-" + ShopFixtures.unique());
                jdbcTemplate.update("INSERT INTO promotion_products (promotion_id, position, product_id) VALUES (?, 0, ?)",
                        UuidBinary.toBytes(promotionId), UuidBinary.toBytes(productId));
            };
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
