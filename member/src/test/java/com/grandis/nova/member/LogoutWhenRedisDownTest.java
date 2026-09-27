package com.grandis.nova.member;

import com.grandis.nova.member.support.MemberIntegrationTest;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.grandis.nova.common.security.JwtAuthenticationFilter;
import com.grandis.nova.common.security.JwtTokenProvider;
import com.grandis.nova.common.security.RevocationChecker;
import com.grandis.nova.common.security.Role;
import com.grandis.nova.common.security.TokenType;
import com.grandis.nova.common.web.RequestIdFilter;
import com.grandis.nova.member.auth.api.AuthCookies;
import com.grandis.nova.member.auth.application.AdminRefreshTokenStore;
import com.grandis.nova.member.auth.application.RefreshTokenStore;
import com.grandis.nova.member.auth.application.RevocationStore;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * "DELETE /session 은 열린 경로. 표식을 못 심으면 쿠키는 지우되 503 retryable 로 알린다". Redis 쪽 저장소와 체커를 모킹해 죽은 상태를 만든다.
 * 셋 다 죽은 경우 외에 한쪽만 실패하는 두 갈래를 따로 둔다 — 그래야 "하나가 실패해도 다른 하나는 시도한다" 가 시험에 잡힌다.
 * 표식을 못 심으면 액세스는 만료까지, 리프레시를 못 지우면 이미 리프레시를 가진 쪽은 14d 까지 — 감수하는 위험 상한.
 */
@MemberIntegrationTest
@DisplayName("DELETE /session — Redis 가 죽으면 503 retryable, 쿠키는 그래도 지운다")
class LogoutWhenRedisDownTest {



    @Autowired WebApplicationContext context;
    @Autowired FilterChainProxy springSecurityFilterChain;
    @Autowired RequestIdFilter requestIdFilter;
    @Autowired JwtTokenProvider provider;
    @MockitoBean RefreshTokenStore refreshTokens;              // 회원 리프레시 정본(DB) — 여기서는 죽은 저장소를 흉내 낸다
    @MockitoBean AdminRefreshTokenStore adminRefreshTokens;
    @MockitoBean RevocationStore revocations;
    @MockitoBean RevocationChecker checker;   // 필터의 폐기 조회도 죽은 상태로: DELETE /session 은 열린 경로라 통과해야 한다

    private static final DataAccessException DOWN = new RedisConnectionFailureException("down");

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context).addFilters(requestIdFilter, springSecurityFilterChain).build();
    }

    private String accessToken() {
        return provider.create("101", Role.USER, UUID.randomUUID(), TokenType.ACCESS);
    }

    @Test
    @DisplayName("저장소·체커가 전부 예외를 던지면 503 DEPENDENCY_UNAVAILABLE(details.retryable=true) 이고 refresh 쿠키는 만료로 내려온다")
    void logoutIs503RetryableWhenRedisDown() throws Exception {
        doThrow(DOWN).when(refreshTokens).revokeSession(any());
        doThrow(DOWN).when(revocations).revokeSession(any(), any());
        doThrow(new com.grandis.nova.common.security.RevocationCheckFailedException(DOWN)).when(checker).isRevoked(any());

        mvc().perform(delete("/api/v1/session").header(JwtAuthenticationFilter.HEADER, accessToken()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("DEPENDENCY_UNAVAILABLE"))
                .andExpect(jsonPath("$.error.details.retryable").value(true))
                .andExpect(cookie().maxAge(AuthCookies.REFRESH_TOKEN, 0))
                .andExpect(cookie().maxAge(AuthCookies.ADMIN_REFRESH_TOKEN, 0));   // 관리자 쿠키도 같이 만료돼야 한다
    }

    @Test
    @DisplayName("리프레시 조회가 죽어도 헤더의 sid 는 끊고 503 + 쿠키 만료 — 조회 예외가 500 으로 새어 폐기를 통째로 건너뛰지 않는다")
    void refreshLookupFailureStillRevokesTheHeaderSession() throws Exception {
        doThrow(DOWN).when(refreshTokens).find(any());

        mvc().perform(delete("/api/v1/session")
                        .header(JwtAuthenticationFilter.HEADER, accessToken())
                        .cookie(new jakarta.servlet.http.Cookie(AuthCookies.REFRESH_TOKEN, "opaque-token")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("DEPENDENCY_UNAVAILABLE"))
                .andExpect(jsonPath("$.error.details.retryable").value(true))
                .andExpect(cookie().maxAge(AuthCookies.REFRESH_TOKEN, 0))
                .andExpect(cookie().maxAge(AuthCookies.ADMIN_REFRESH_TOKEN, 0));

        verify(revocations).revokeSession(any(), any());   // 헤더에서 얻은 sid 는 그래도 끊었다
    }

    @Test
    @DisplayName("리프레시 폐기만 실패: 503 이지만 sid 표식은 심어졌다 (액세스는 즉시 죽는다)")
    void markStillWrittenWhenDeleteFails() throws Exception {
        doThrow(DOWN).when(refreshTokens).revokeSession(any());

        mvc().perform(delete("/api/v1/session").header(JwtAuthenticationFilter.HEADER, accessToken()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("DEPENDENCY_UNAVAILABLE"))
                .andExpect(jsonPath("$.error.details.retryable").value(true))
                .andExpect(cookie().maxAge(AuthCookies.REFRESH_TOKEN, 0))
                .andExpect(cookie().maxAge(AuthCookies.ADMIN_REFRESH_TOKEN, 0));   // 관리자 쿠키도 같이 만료돼야 한다

        verify(revocations).revokeSession(any(), any());
    }

    @Test
    @DisplayName("표식 쓰기만 실패: 503 이고 리프레시 폐기는 그래도 시도됐다")
    void refreshStillDeletedWhenMarkFails() throws Exception {
        doThrow(DOWN).when(revocations).revokeSession(any(), any());

        mvc().perform(delete("/api/v1/session").header(JwtAuthenticationFilter.HEADER, accessToken()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("DEPENDENCY_UNAVAILABLE"))
                .andExpect(jsonPath("$.error.details.retryable").value(true))
                .andExpect(cookie().maxAge(AuthCookies.REFRESH_TOKEN, 0))
                .andExpect(cookie().maxAge(AuthCookies.ADMIN_REFRESH_TOKEN, 0));   // 관리자 쿠키도 같이 만료돼야 한다

        verify(refreshTokens).revokeSession(any());
    }
}
