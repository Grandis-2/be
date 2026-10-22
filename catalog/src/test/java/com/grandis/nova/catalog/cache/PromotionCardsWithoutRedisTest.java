package com.grandis.nova.catalog.cache;

import com.grandis.nova.catalog.listing.ProductListingQueryRepository;
import com.grandis.nova.catalog.support.CatalogIntegrationTest;
import com.grandis.nova.catalog.support.RedisContainerConfig;
import com.grandis.nova.catalog.support.ShopFixtures;
import com.grandis.nova.common.UuidBinary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** 상품 세대를 Redis 에서 못 읽을 때의 홍보 카드 — 캐시에 넣지 않되 같은 키의 동시 조회는 DB 한 번으로 합친다. */
@CatalogIntegrationTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "nova.read-cache.enabled=true")
class PromotionCardsWithoutRedisTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired CatalogLocalCaches local;
    @MockitoSpyBean ProductListingQueryRepository listings;

    ShopFixtures fixtures;
    UUID categoryId;

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
        categoryId = fixtures.category();
        local.promotionCards().invalidateAll();
    }

    @Test
    @DisplayName("Redis 가 멈춰 세대를 못 읽어도 같은 홍보의 동시 요청은 카드를 DB 에서 한 번만 읽는다 — 그 결과는 넣지 않아 다음 요청은 다시 읽는다")
    void unreadableGenerationStillSharesOneLoad() throws Exception {
        UUID productId = fixtures.product(categoryId, "PREORDER", "ACTIVE", "장애 중 카드", "t");
        fixtures.registration(productId);
        fixtures.option(productId, "ACTIVE");
        Instant now = Instant.now();
        fixtures.campaign(productId, now.minus(Duration.ofHours(1)), now.plus(Duration.ofHours(1)));
        UUID promotionId = UUID.randomUUID();
        // 관리자 API 는 업로드된 사진 행이 필요하다 — 읽기 경로만 보므로 행으로 넣는다(한국 날짜와 DB 날짜가 달라도 오늘이 기간 안이게 앞뒤 하루)
        jdbcTemplate.update("""
                INSERT INTO promotions (id, idempotency_key, name, start_date, end_date, thumbnail_url, detail_image_urls, created_at, updated_at)
                VALUES (?, ?, '장애 중 홍보', CURDATE() - INTERVAL 1 DAY, CURDATE() + INTERVAL 1 DAY, 'https://img/t.jpg', JSON_ARRAY(), NOW(6), NOW(6))
                """, UuidBinary.toBytes(promotionId), "k-" + ShopFixtures.unique());
        jdbcTemplate.update("INSERT INTO promotion_products (promotion_id, position, product_id) VALUES (?, 0, ?)",
                UuidBinary.toBytes(promotionId), UuidBinary.toBytes(productId));

        AtomicInteger loads = new AtomicInteger();
        doAnswer(invocation -> {
            loads.incrementAndGet();
            Thread.sleep(1_500);   // 동시 요청이 이 조회에 붙을 시간
            return invocation.callRealMethod();
        }).when(listings).findCards(anyList(), anyBoolean());

        int requests = 6;
        ExecutorService pool = Executors.newFixedThreadPool(requests);
        RedisContainerConfig.pause();
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<org.springframework.mock.web.MockHttpServletResponse>> results = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                results.add(pool.submit(() -> {
                    go.await();
                    return mockMvc.perform(get("/api/v1/promotions/{id}", promotionId)).andReturn().getResponse();
                }));
            }
            go.countDown();
            for (Future<org.springframework.mock.web.MockHttpServletResponse> result : results) {
                org.springframework.mock.web.MockHttpServletResponse response = result.get(30, TimeUnit.SECONDS);
                assertThat(response.getStatus()).isEqualTo(200);
                assertThat(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).as("기다린 쪽도 실제 카드를 받는다").contains("장애 중 카드");
            }
            assertThat(loads).as("동시 요청은 DB 한 번을 나눠 받는다").hasValue(1);

            assertThat(mockMvc.perform(get("/api/v1/promotions/{id}", promotionId)).andReturn().getResponse().getStatus()).isEqualTo(200);
            assertThat(loads).as("세대를 못 읽은 결과는 넣지 않는다 — 다음 요청은 다시 읽는다").hasValue(2);
        } finally {
            RedisContainerConfig.unpause();
            pool.shutdownNow();
        }
    }
}
