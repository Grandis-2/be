package com.grandis.nova.catalog.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.grandis.nova.catalog.support.AccessTokens;
import com.grandis.nova.catalog.support.CatalogIntegrationTest;
import com.grandis.nova.catalog.support.ShopFixtures;
import com.grandis.nova.common.security.AuthRedisKeys;
import com.grandis.nova.common.security.JwtTokenProvider;
import com.grandis.nova.common.security.RevocationChecker;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 공개 조회(SecurityConfig.ANONYMOUS_READS)는 토큰을 보지 않는다 — 헤더가 있어도 파싱 · 폐기 조회를 하지 않고, 응답은 토큰 없는 요청과 같다.
 * 신원을 읽는 경로(내 리뷰 · 내부 조회 · 집계 신호)는 그대로 토큰을 본다.
 */
@CatalogIntegrationTest
@AutoConfigureMockMvc
class AnonymousReadsApiTest {

    private static final UUID CUSTOMER = UUID.fromString("00000000-0000-7000-8000-000000000791");

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired StringRedisTemplate redis;
    @MockitoSpyBean JwtTokenProvider provider;
    @MockitoSpyBean RevocationChecker revocationChecker;

    UUID productId;

    @BeforeEach
    void setUp() {
        ShopFixtures fixtures = new ShopFixtures(jdbcTemplate);
        productId = fixtures.product("IN_STOCK", "ACTIVE");
        fixtures.registration(productId);
        fixtures.option(productId, "ACTIVE");
        fixtures.stockReady(productId);
    }

    /** 요청마다 새로 만든다 — 빌더에 붙인 토큰은 다음 요청에 남는다 */
    private List<Supplier<MockHttpServletRequestBuilder>> anonymousReads() {
        UUID missing = UUID.fromString("00000000-0000-7000-8000-0000000007ff");
        return List.of(
                () -> get("/api/v1/products"),
                () -> get("/api/v1/products/{id}", productId),
                () -> get("/api/v1/products/{id}", missing),
                () -> get("/api/v1/products/{id}/variants/{variantId}", productId, missing),
                () -> get("/api/v1/products/{id}/reviews", productId),
                () -> get("/api/v1/products/ranking"),
                () -> get("/api/v1/products/searches/popular"),
                () -> get("/api/v1/categories"),
                () -> get("/api/v1/promotions"),
                () -> get("/api/v1/promotions/{id}", missing),
                () -> get("/api/v1/banners"),
                () -> get("/api/v1/reviews"));
    }

    @Test
    @DisplayName("공개 조회 — 유효 · 폐기 · 만료 · 모르는 키 · 모양이 깨진 토큰 모두 토큰 없는 요청과 같은 상태이고, 파싱 · 폐기 조회를 한 번도 하지 않는다")
    void anonymousReadsIgnoreTheToken() throws Exception {
        String revoked = AccessTokens.customerToken(CUSTOMER);
        redis.opsForValue().set(AuthRedisKeys.revokedSession(provider.parse(revoked).sessionId()), "1", Duration.ofMinutes(5));
        List<String> tokens = List.of(AccessTokens.customerToken(CUSTOMER), revoked, AccessTokens.expiredCustomerToken(CUSTOMER),
                AccessTokens.foreignCustomerToken(CUSTOMER), AccessTokens.adminToken(), "not-a-jwt");
        clearInvocations(provider, revocationChecker);

        for (Supplier<MockHttpServletRequestBuilder> read : anonymousReads()) {
            var anonymous = mockMvc.perform(read.get()).andReturn();
            assertThat(anonymous.getResponse().getStatus()).as("공개라 인가가 막지 않는다 " + anonymous.getRequest().getRequestURI())
                    .isNotIn(401, 403);
            for (String token : tokens) {
                int withToken = mockMvc.perform(read.get().with(AccessTokens.withToken(token))).andReturn().getResponse().getStatus();
                assertThat(withToken).as(anonymous.getRequest().getRequestURI()).isEqualTo(anonymous.getResponse().getStatus());
            }
        }
        verify(provider, never()).parse(anyString());
        verify(revocationChecker, never()).isRevoked(any());
    }

    @Test
    @DisplayName("신원을 읽는 경로는 그대로 토큰을 본다 — 내 리뷰 · 내부 조회는 파싱 · 폐기 조회를 하고 폐기 토큰은 401(쓸 수 있는 주문상품도), 조회 신호는 회원 토큰을 읽는다")
    void identityPathsStillCheckTheToken() throws Exception {
        String token = AccessTokens.customerToken(CUSTOMER);
        clearInvocations(provider, revocationChecker);
        mockMvc.perform(get("/api/v1/reviews/mine").with(AccessTokens.withToken(token))).andExpect(status().isOk());
        mockMvc.perform(get("/internal/products/{id}/options", productId).with(AccessTokens.withToken(token))).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/products/{id}/views", productId).with(AccessTokens.withToken(token))).andExpect(status().isNoContent());
        verify(provider, times(3)).parse(token);
        verify(revocationChecker, times(3)).isRevoked(any());

        redis.opsForValue().set(AuthRedisKeys.revokedSession(provider.parse(token).sessionId()), "1", Duration.ofMinutes(5));
        mockMvc.perform(get("/api/v1/reviews/mine").with(AccessTokens.withToken(token)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
        mockMvc.perform(get("/internal/products/{id}/options", productId).with(AccessTokens.withToken(token))).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/reviews/writable").with(AccessTokens.withToken(token))).andExpect(status().isUnauthorized());
    }
}
