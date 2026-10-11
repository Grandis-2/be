package com.grandis.nova.payment.config;

import com.grandis.nova.common.security.BearerTokens;
import com.grandis.nova.common.security.JwtTokenProvider;
import com.grandis.nova.common.security.RevocationCheckFailedException;
import com.grandis.nova.common.security.RevocationChecker;
import com.grandis.nova.common.security.Role;
import com.grandis.nova.common.security.TokenType;
import com.grandis.nova.payment.support.PaymentIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 진짜 토큰(Authorization: Bearer)으로 보안 체인 전체를 태운다 — 필터 · 폐기 조회 정책 · 인가 규칙.
 * Redis 는 없다. 폐기 조회는 대역으로 세 가지 답(폐기 아님 · 폐기 · 조회 실패)을 만든다.
 */
@PaymentIntegrationTest
@AutoConfigureMockMvc
class SecurityRulesTest {

    static final String ATTEMPTS = "/internal/payment-attempts";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JwtTokenProvider tokens;

    @MockitoBean
    RevocationChecker revocationChecker;

    String user;
    String admin;

    @BeforeEach
    void setUp() {
        user = tokens.create("101", Role.USER, UUID.randomUUID(), TokenType.ACCESS);
        admin = tokens.create("admin", Role.ADMIN, UUID.randomUUID(), TokenType.ACCESS);
    }

    // ── /internal/** : 호출자(order · draw)가 싣는 사용자 토큰으로 인증된다 ─────────────────────

    @Test
    void internalApiAcceptsForwardedUserToken() throws Exception {
        perform(openCapture(), user).andExpect(status().isCreated());
    }

    @Test
    void internalApiWithoutTokenIsUnauthorizedEnvelope() throws Exception {
        mockMvc.perform(openCapture())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    @Test
    void revokedTokenIsUnauthorized() throws Exception {
        given(revocationChecker.isRevoked(any())).willReturn(true);

        perform(openCapture(), user).andExpect(status().isUnauthorized());
    }

    // 폐기 조회 실패 때 내부 API 는 닫는다 — 결제 흐름은 order 에서 이미 닫히므로 잃는 것이 없고, 호출자가 닫기를 빠뜨려도 여기서 막는다.
    @Test
    void internalApiRejectsWhenLookupFails() throws Exception {
        given(revocationChecker.isRevoked(any()))
                .willThrow(new RevocationCheckFailedException(new IllegalStateException("redis down")));

        perform(openCapture(), user)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.error.details.retryable").value(true));
    }

    // ── 나머지 거부: 규칙 없이 추가된 경로가 열린 채 배포되지 않는다 ─────────────────────────

    @Test
    void unknownPathIsDeniedEvenWhenAuthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/payments")).andExpect(status().isUnauthorized());
        perform(get("/api/v1/payments"), user)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        perform(get("/actuator/health"), admin).andExpect(status().isForbidden());
    }

    private ResultActions perform(MockHttpServletRequestBuilder request, String accessToken) throws Exception {
        return mockMvc.perform(request.header(BearerTokens.HEADER, BearerTokens.value(accessToken)));
    }

    private static MockHttpServletRequestBuilder openCapture() {
        return post(ATTEMPTS).contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetType\":\"ORDER\",\"targetId\":\"%s\",\"amount\":1000}"
                        .formatted(UUID.randomUUID()));
    }
}
