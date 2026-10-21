package com.grandis.nova.catalog.integration;

import com.grandis.nova.catalog.support.CatalogIntegrationTest;
import com.grandis.nova.catalog.support.ShopFixtures;
import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 공개 회차 캐시를 켠 채 — 회원 상세 · 후기 · 조회 신호 · 홍보 카드는 회차를 캐시에서 꺼내고(preorder 를 다시 부르지 않는다), 세대를 올리면 다시 묻고,
 * 관리자 상세는 캐시를 거치지 않는다. preorder 의 회차 변경은 시험 DB 의 회차 행을 직접 고쳐 흉내 낸다(대역 클라이언트가 그 행을 읽는다).
 */
@CatalogIntegrationTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {"nova.read-cache.enabled=true", "nova.read-cache.preorder-campaign-ttl=1h"})
class PublicCampaignsApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired StringRedisTemplate redis;
    @MockitoSpyBean PreorderCampaignClient client;

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
    @DisplayName("회원 상세 · 후기 · 조회 신호는 회차를 한 번만 묻는다 — preorder 가 조기 마감해도 세대를 올리기 전에는 캐시 값, 올리면 바로 마감. 관리자 상세는 늘 새 값")
    void detailReadsCampaignFromCache() throws Exception {
        UUID productId = openPreorder("캐시 회차");
        clearInvocations(client);

        assertThat(detailStatus(productId)).isEqualTo("OPEN");
        mockMvc.perform(get("/api/v1/products/{id}/reviews", productId)).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/products/{id}/views", productId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"anonymousId\":\"%s\"}".formatted(UUID.randomUUID()))).andExpect(status().isNoContent());
        assertThat(detailStatus(productId)).isEqualTo("OPEN");
        verify(client, times(1)).getCampaigns(any());

        closeNow(productId);   // preorder 가 조기 마감했다 — catalog 는 아직 모른다
        assertThat(detailStatus(productId)).as("세대를 올리기 전에는 캐시 값").isEqualTo("OPEN");
        assertThat(data(mockMvc.perform(get("/api/v1/admin/products/{id}", productId).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())).get("product").get("campaign").get("status").asString())
                .as("관리자 상세는 캐시를 거치지 않는다").isEqualTo("CLOSED");

        redis.opsForValue().increment("catalog:cache:gen:" + PublicCampaigns.CAMPAIGNS);
        assertThat(detailStatus(productId)).isEqualTo("CLOSED");
    }

    @Test
    @DisplayName("회차 없음(판매 준비 전)도 캐시한다 — 회차 행이 생겨도 세대를 올리기 전에는 404, 올리면 200")
    void missingCampaignIsCached() throws Exception {
        UUID productId = fixtures.product(categoryId, "PREORDER", "ACTIVE", "준비 전", "t");
        fixtures.registration(productId);
        fixtures.option(productId, "ACTIVE");
        mockMvc.perform(get("/api/v1/products/{id}", productId)).andExpect(status().isNotFound());

        Instant now = Instant.now();
        fixtures.campaign(productId, now.minus(Duration.ofHours(1)), now.plus(Duration.ofHours(1)));
        mockMvc.perform(get("/api/v1/products/{id}", productId)).andExpect(status().isNotFound());

        redis.opsForValue().increment("catalog:cache:gen:" + PublicCampaigns.CAMPAIGNS);
        assertThat(detailStatus(productId)).isEqualTo("OPEN");
    }

    @Test
    @DisplayName("preorder 가 답하지 않으면 넣지 않는다 — 그 요청은 503, 다음 요청은 다시 물어 200")
    void failureIsNotCached() throws Exception {
        UUID productId = openPreorder("장애 회차");
        doThrow(new BusinessException(CommonErrorCode.DEPENDENCY_UNAVAILABLE)).when(client).getCampaigns(any());
        mockMvc.perform(get("/api/v1/products/{id}", productId)).andExpect(status().isServiceUnavailable());

        doCallRealMethod().when(client).getCampaigns(any());
        clearInvocations(client);
        assertThat(detailStatus(productId)).isEqualTo("OPEN");
        verify(client, times(1)).getCampaigns(any());
    }

    @Test
    @DisplayName("홍보 카드는 묶음 하나로 한 번 묻는다 — 두 번째 상세는 preorder 를 부르지 않고, 세대를 올리면 조기 마감이 카드에 보인다")
    void promotionCardsAreCachedAsOneSet() throws Exception {
        UUID first = openPreorder("홍보 하나");
        UUID second = openPreorder("홍보 둘");
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        String body = """
                { "name": "회차 홍보", "startDate": "%s", "endDate": "%s", "thumbnailUrl": "https://img/t.jpg", "detailImageUrls": [],
                  "productIds": ["%s", "%s"] }
                """.formatted(today, today, first, second);
        UUID promotionId = UUID.fromString(data(mockMvc.perform(post("/api/v1/admin/promotions")
                .header("Idempotency-Key", "k-" + ShopFixtures.unique()).contentType(MediaType.APPLICATION_JSON).content(body)
                .with(user("admin").roles("ADMIN"))).andExpect(status().isCreated())).get("promotionId").asString());
        clearInvocations(client);

        assertThat(cardStatuses(promotionId)).containsExactlyInAnyOrderEntriesOf(java.util.Map.of(first, "OPEN", second, "OPEN"));
        assertThat(cardStatuses(promotionId)).containsExactlyInAnyOrderEntriesOf(java.util.Map.of(first, "OPEN", second, "OPEN"));
        verify(client, times(1)).getCampaigns(any());

        closeNow(second);
        assertThat(cardStatuses(promotionId)).as("세대를 올리기 전에는 캐시 값").containsExactlyInAnyOrderEntriesOf(java.util.Map.of(first, "OPEN", second, "OPEN"));
        redis.opsForValue().increment("catalog:cache:gen:" + PublicCampaigns.CAMPAIGNS);
        assertThat(cardStatuses(promotionId)).containsExactlyInAnyOrderEntriesOf(java.util.Map.of(first, "OPEN", second, "CLOSED"));
    }

    /** 접수 중인 사전예약 상품 하나(공개 · 옵션 하나 · 1시간 전 오픈 · 1시간 뒤 마감). */
    private UUID openPreorder(String title) {
        UUID productId = fixtures.product(categoryId, "PREORDER", "ACTIVE", title, "t");
        fixtures.registration(productId);
        fixtures.option(productId, "ACTIVE");
        Instant now = Instant.now();
        fixtures.campaign(productId, now.minus(Duration.ofHours(1)), now.plus(Duration.ofHours(1)));
        return productId;
    }

    private void closeNow(UUID productId) {
        jdbcTemplate.update("UPDATE preorder_campaigns SET closes_at = UTC_TIMESTAMP(6) - INTERVAL 1 MINUTE WHERE product_id = ?",
                (Object) UuidBinary.toBytes(productId));
    }

    private String detailStatus(UUID productId) throws Exception {
        return data(mockMvc.perform(get("/api/v1/products/{id}", productId)).andExpect(status().isOk())).get("campaign").get("status").asString();
    }

    /** 홍보 상세의 카드별 접수 단계 — 상품 id → 단계. */
    private java.util.Map<UUID, String> cardStatuses(UUID promotionId) throws Exception {
        java.util.Map<UUID, String> statuses = new java.util.HashMap<>();
        data(mockMvc.perform(get("/api/v1/promotions/{id}", promotionId)).andExpect(status().isOk())).get("products")
                .forEach(card -> statuses.put(UUID.fromString(card.get("productId").asString()), card.get("preorderStatus").asString()));
        return statuses;
    }

    private static JsonNode data(ResultActions actions) throws Exception {
        return JSON.readTree(actions.andReturn().getResponse().getContentAsString()).get("data");
    }
}
