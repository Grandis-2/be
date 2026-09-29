package com.grandis.nova.catalog.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.grandis.nova.catalog.support.AccessTokens;
import com.grandis.nova.catalog.support.CatalogIntegrationTest;
import com.grandis.nova.catalog.support.ShopFixtures;
import com.grandis.nova.common.security.AuthRedisKeys;
import com.grandis.nova.common.security.BearerTokens;
import com.grandis.nova.common.security.JwtTokenProvider;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * common:security 채택(NV-139)의 실측 — catalog 의 체인이 실제 토큰을 검증하고, 폐기 표식을 읽고, 401/403 을 봉투로 내는가.
 * 서명 · 만료 · 봉투의 세부는 common:security 의 시험이 맡는다. 여기서는 catalog 의 경로 규칙과 배선만 본다.
 */
@CatalogIntegrationTest
@AutoConfigureMockMvc
class TokenAuthenticationApiTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired StringRedisTemplate redis;
    @Autowired JwtTokenProvider provider;

    Long productId;

    @BeforeEach
    void setUp() {
        ShopFixtures fixtures = new ShopFixtures(jdbcTemplate);
        productId = fixtures.product("IN_STOCK", "ACTIVE");
        fixtures.completeRegistration(productId);
    }

    @Test
    @DisplayName("실제 RS256 토큰 — 회원은 내부 조회 200 · 관리자 목록 403 봉투, 관리자는 둘 다 200, 토큰 없으면 내부 조회 401 봉투 · 공개 상세는 200")
    void realTokensPassTheChain() throws Exception {
        mockMvc.perform(get("/internal/products/{id}/options", productId).with(AccessTokens.customer(657L))).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/admin/products").with(AccessTokens.customer(657L)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        mockMvc.perform(get("/internal/products/{id}/options", productId).with(AccessTokens.admin())).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/admin/products").with(AccessTokens.admin())).andExpect(status().isOk());
        mockMvc.perform(get("/internal/products/{id}/options", productId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
        mockMvc.perform(get("/api/v1/products/{id}", productId)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("관리자 미리보기도 실제 토큰으로 — 비공개 상품이 관리자 토큰이면 200, 회원 토큰이면 404, 응답은 no-store")
    void adminPreviewWithRealToken() throws Exception {
        jdbcTemplate.update("UPDATE products SET visible = 0 WHERE id = ?", productId);
        mockMvc.perform(get("/api/v1/products/{id}", productId).with(AccessTokens.customer(657L))).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/products/{id}", productId).with(AccessTokens.admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.visible").value(false))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
    }

    @Test
    @DisplayName("틀린 토큰은 익명과 같다 — 모르는 키 서명 · 만료 · 옛 헤더(X-Session-Token) · Bearer 가 아닌 스킴은 내부 조회 401, 공개 상세는 200")
    void badTokensAreAnonymous() throws Exception {
        for (String token : new String[] {AccessTokens.foreignCustomerToken(657L), AccessTokens.expiredCustomerToken(657L)}) {
            mockMvc.perform(get("/internal/products/{id}/options", productId).with(AccessTokens.withToken(token)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
            mockMvc.perform(get("/api/v1/products/{id}", productId).with(AccessTokens.withToken(token))).andExpect(status().isOk());
        }
        String valid = AccessTokens.customerToken(657L);
        mockMvc.perform(get("/internal/products/{id}/options", productId).header("X-Session-Token", valid))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/internal/products/{id}/options", productId).header(BearerTokens.HEADER, "Token " + valid))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("폐기 표식을 읽는다 — 세션 폐기(revoked-sid) · 회원 not-before 가 Redis 에 있으면 그 토큰은 401")
    void revocationMarksAreHonoured() throws Exception {
        String token = AccessTokens.customerToken(658L);
        mockMvc.perform(get("/internal/products/{id}/options", productId).with(AccessTokens.withToken(token))).andExpect(status().isOk());

        redis.opsForValue().set(AuthRedisKeys.revokedSession(provider.parse(token).sessionId()), "1", Duration.ofMinutes(5));
        mockMvc.perform(get("/internal/products/{id}/options", productId).with(AccessTokens.withToken(token)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));

        String another = AccessTokens.customerToken(659L);
        try {
            // iat ≤ nbf 면 거부 — 같은 초 발급도 거부라 지금 시각을 심으면 방금 발급한 토큰이 걸린다
            redis.opsForValue().set(AuthRedisKeys.notBefore("659"), String.valueOf(java.time.Instant.now().getEpochSecond()), Duration.ofMinutes(5));
            mockMvc.perform(get("/internal/products/{id}/options", productId).with(AccessTokens.withToken(another))).andExpect(status().isUnauthorized());
        } finally {
            redis.delete(AuthRedisKeys.notBefore("659"));
        }
    }

    @Test
    @DisplayName("공개 목록 밖의 경로는 거부다 — 익명 401 봉투, 회원 토큰이면 403 봉투. 공개 조회는 GET 만(POST 상품은 거부)")
    void unlistedPathsAreDenied() throws Exception {
        mockMvc.perform(get("/api/v1/unknown"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
        mockMvc.perform(get("/api/v1/unknown").with(AccessTokens.customer(657L)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/products/{id}", productId))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/categories")).andExpect(status().isOk());
    }
}
