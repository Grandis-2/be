package com.grandis.nova.catalog.event;

import com.grandis.nova.catalog.integration.PreorderCampaignClient;
import com.grandis.nova.catalog.integration.PublicCampaigns;
import com.grandis.nova.catalog.support.CatalogIntegrationTest;
import com.grandis.nova.catalog.support.ShopFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 읽기 캐시 전체를 끈 인스턴스(시험 기본 nova.read-cache.enabled=false)에 공개 회차 캐시 TTL 만 적은 경우 — 운영의 차단 스위치를 내린 상태.
 */
@CatalogIntegrationTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "nova.read-cache.preorder-campaign-ttl=1h")
class PreorderCampaignScheduleChangedReadCacheOffTest {

    private static final String GENERATION = "catalog:cache:gen:" + PublicCampaigns.CAMPAIGNS;

    @Autowired PreorderCampaignScheduleChangedHandler handler;
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired MockMvc mockMvc;
    @MockitoSpyBean PreorderCampaignClient client;

    @Test
    @DisplayName("읽기 캐시를 꺼도 일정 변경을 받으면 세대를 올린다 — 같은 큐를 받는 켠 인스턴스의 캐시를 비우게")
    void bumpsGenerationWithReadCacheOff() {
        redis.opsForValue().set(GENERATION, "0");   // 시작점을 정해 둔다 — 없는 세대는 지금 시각에서 시작한다
        handler.handle(UUID.randomUUID(), null);
        assertThat(redis.opsForValue().get(GENERATION)).isEqualTo("1");
    }

    @Test
    @DisplayName("읽기 캐시를 끄면 TTL 이 있어도 공개 회차를 캐시하지 않고 fresh 없이 한 번 묻는다 — 요청마다 preorder DB 로 보내지 않게")
    void publicCampaignsOffWithReadCacheOff() throws Exception {
        ShopFixtures fixtures = new ShopFixtures(jdbcTemplate);
        UUID product = fixtures.product(fixtures.category(), "PREORDER", "ACTIVE", "차단 스위치", "t");
        fixtures.registration(product);
        fixtures.option(product, "ACTIVE");
        Instant now = Instant.now();
        fixtures.campaign(product, now.minus(Duration.ofHours(1)), now.plus(Duration.ofHours(1)));
        clearInvocations(client);

        mockMvc.perform(get("/api/v1/products/{id}", product)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/products/{id}", product)).andExpect(status().isOk());

        verify(client, times(2)).getCampaigns(any(), eq(false));
        verify(client, never()).getCampaigns(any(), eq(true));
    }
}
