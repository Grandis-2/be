package com.grandis.nova.catalog.integration;

import com.grandis.nova.catalog.support.CatalogIntegrationTest;
import com.grandis.nova.catalog.support.ShopFixtures;
import com.grandis.nova.common.redis.cache.ReadCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mockingDetails;

/**
 * 공개 회차 캐시를 켰을 때 preorder 에 가는 호출 수 — 세대 한 번 올리기, 일정 변경이 잇따를 때, Redis 를 못 쓸 때. 인스턴스 하나 기준이고, 인스턴스가
 * N 대면 N 배다(몰림 줄이기는 인스턴스 안에서만 한다). fresh=true 는 preorder 의 회차 캐시를 건너뛰어 preorder DB 를 읽는다.
 */
@CatalogIntegrationTest
// Redis 제한 시간을 넘긴 호출은 캐시를 건너뛰어 fresh 없이 한 번 더 묻는다(우회) — 호출 수를 재는 이 시험에 그 경로가 섞이지 않게 시험 기본값(300ms)보다 넉넉히 둔다
@TestPropertySource(properties = {"nova.read-cache.enabled=true", "nova.read-cache.preorder-campaign-ttl=3h", "spring.data.redis.timeout=5s"})
class PublicCampaignsLoadTest {

    private static final int REQUESTS = 50;
    private static final String GENERATION = "catalog:cache:gen:" + PublicCampaigns.CAMPAIGNS;

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired StringRedisTemplate redis;
    @Autowired ReadCache readCache;
    @MockitoSpyBean PreorderCampaignClient client;
    @Autowired PublicCampaigns publicCampaigns;

    ShopFixtures fixtures;
    UUID productId;

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
        Set<String> keys = redis.keys("catalog:cache:*");
        if (!keys.isEmpty()) {
            redis.delete(keys);
        }
        productId = fixtures.product(fixtures.category(), "PREORDER", "ACTIVE", "부하", "t");
        fixtures.registration(productId);
        fixtures.option(productId, "ACTIVE");
        Instant now = Instant.now();
        fixtures.campaign(productId, now.minus(Duration.ofHours(1)), now.plus(Duration.ofHours(1)));
        publicCampaigns.find(productId);   // 채워 둔다
        clearInvocations(client);
    }

    @Test
    @DisplayName("세대를 한 번 올린 직후 같은 상품에 50 건이 몰려도 preorder 에는 fresh 없이 1 번 · fresh 로 1 번만 묻는다")
    void oneBumpUnderBurst() throws Exception {
        readCache.bumpNow(PublicCampaigns.CAMPAIGNS);
        burst(REQUESTS);
        assertThat(calls(false)).isEqualTo(1);
        assertThat(calls(true)).isEqualTo(1);
    }

    @Test
    @DisplayName("일정 변경이 20 번 잇따르면(바뀔 때마다 10 건 조회) 변경마다 fresh 없이 1 번 · fresh 로 1 번 — 묻는 수는 변경 수에 비례하고 조회 수와는 무관하다")
    void consecutiveBumps() throws Exception {
        int bumps = 20;
        for (int i = 0; i < bumps; i++) {
            readCache.bumpNow(PublicCampaigns.CAMPAIGNS);
            burst(10);
        }
        assertThat(calls(false)).isEqualTo(bumps);
        assertThat(calls(true)).isEqualTo(bumps);
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)   // 실패가 이어져 차단기가 열린다 — 그 상태가 다른 시험에 남지 않게
    @DisplayName("Redis 를 못 쓰면(세대를 못 읽음) 조회마다 fresh 없이 묻는다 — 50 건이면 50 번이고 preorder DB 로 가는 fresh 는 0 번")
    void redisUnavailable() throws Exception {
        redis.opsForValue().set(GENERATION, "not-a-number");
        try {
            for (int i = 0; i < REQUESTS; i++) {
                publicCampaigns.find(productId);
            }
        } finally {
            redis.delete(GENERATION);
        }
        assertThat(calls(false)).isEqualTo(REQUESTS);
        assertThat(calls(true)).isZero();
    }

    private void burst(int requests) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(requests);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    assertThat(publicCampaigns.find(productId)).isPresent();
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private long calls(boolean fresh) {
        return mockingDetails(client).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("getCampaigns"))
                .filter(invocation -> invocation.getArgument(1).equals(fresh))
                .count();
    }
}
