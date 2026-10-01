package com.grandis.nova.order.config;

import com.grandis.nova.common.security.BearerTokens;
import com.grandis.nova.common.security.JwtTokenProvider;
import com.grandis.nova.common.security.RevocationCheckFailedException;
import com.grandis.nova.common.security.RevocationChecker;
import com.grandis.nova.common.security.Role;
import com.grandis.nova.common.security.TokenType;
import com.grandis.nova.order.client.preorder.PreorderClient;
import com.grandis.nova.order.support.OrderIntegrationTest;
import com.grandis.nova.order.support.PreorderStubs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 진짜 토큰(Authorization: Bearer)으로 보안 체인 전체를 태운다 — 인증 흉내(TestAuth)가 건너뛰는 필터 · 폐기 조회 정책 · 인가 규칙.
 * Redis 는 없다. 폐기 조회는 대역으로 세 가지 답(폐기 아님 · 폐기 · 조회 실패)을 만든다.
 */
@OrderIntegrationTest
@AutoConfigureMockMvc
class SecurityRulesTest {

    static final String CUSTOMER = "101";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JwtTokenProvider tokens;

    @MockitoBean
    RevocationChecker revocationChecker;

    @MockitoBean
    PreorderClient preorderClient;

    String user;
    String admin;

    @BeforeEach
    void setUp() {
        user = tokens.create(CUSTOMER, Role.USER, UUID.randomUUID(), TokenType.ACCESS);
        admin = tokens.create("admin", Role.ADMIN, UUID.randomUUID(), TokenType.ACCESS);
    }

    // ── /internal/** (9): preorder 가 싣는 사용자 토큰으로 인증된다 ─────────────────────────

    @Test
    void internalApiAcceptsForwardedUserToken() throws Exception {
        perform(get(cancelability()), user)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cancelable").value(true));
    }

    @Test
    void internalApiAcceptsForwardedAdminToken() throws Exception {
        perform(get(cancelability()), admin).andExpect(status().isOk());
    }

    @Test
    void internalApiWithoutTokenIsUnauthorizedEnvelope() throws Exception {
        mockMvc.perform(get(cancelability()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    // ── 헤더(NV-137) ──────────────────────────────────────────────────────────────

    // 옛 헤더는 더 이상 읽지 않는다 — 토큰이 없는 것과 같다.
    @Test
    void legacySessionHeaderIsNotAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/orders").header("X-Session-Token", user))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void revokedTokenIsUnauthorized() throws Exception {
        given(revocationChecker.isRevoked(any())).willReturn(true);

        perform(get("/api/v1/orders"), user)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.details.retryable").doesNotExist());
    }

    // ── 폐기 조회 실패(NV-138): 닫는 경로는 401 retryable, 나머지는 통과 ────────────────────

    /*
     * 닫힌 경로면 컨트롤러에 닿기 전에 401 retryable 이다. 열린 경로였다면 컨트롤러가 404(없는 주문 · 매핑 없음)로 답했을
     * 것이다(아래 lookupSucceeds... 가 그 대조군). 결제 승인 · 취소 · 배송지 변경은 아직 엔드포인트가 없다(결제 준비는 아래 따로 본다).
     */
    @ParameterizedTest
    @CsvSource({
            "GET,   /api/v1/admin/orders,                         admin",
            "POST,  /api/v1/orders/o-1/payment-attempts,          user",
            "POST,  /api/v1/orders/o-1/payment-attempts/t-1/confirm, user",
            "POST,  /api/v1/orders/o-1/cancel,                    user",
            "PATCH, /api/v1/orders/o-1/shipping-address,          user"
    })
    void failClosedPathsRejectWhenLookupFails(String method, String path, String who) throws Exception {
        lookupFails();

        perform(request(HttpMethod.valueOf(method), path), token(who))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.error.details.retryable").value(true));
    }

    @ParameterizedTest
    @CsvSource({
            "POST,  /api/v1/orders/o-1/cancel",
            "PATCH, /api/v1/orders/o-1/shipping-address"
    })
    void lookupSucceedsSamePathsReachMvc(String method, String path) throws Exception {
        perform(request(HttpMethod.valueOf(method), path), user).andExpect(status().isNotFound());
    }

    // 결제 준비는 엔드포인트가 있다 — 매핑 없는 404(NOT_FOUND)가 아니라 컨트롤러의 404(ORDER_NOT_FOUND)여야 MVC 에 닿은 것이다.
    @Test
    void lookupSucceedsPaymentAttemptReachesController() throws Exception {
        perform(post("/api/v1/orders/o-1/payment-attempts"), user)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
    }

    // 조회(GET)는 같은 접두라도 닫지 않는다 — 메서드를 붙인 항목이라서.
    @Test
    void readsStayOpenWhenLookupFails() throws Exception {
        lookupFails();

        perform(get("/api/v1/orders"), user).andExpect(status().isOk());
        perform(get("/api/v1/orders/o-1"), user)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
    }

    // 주문 생성은 열어 둔다(2026-09-29 결정). 필터를 지나 preorder 조회까지 간다.
    @Test
    void placeOrderStaysOpenWhenLookupFails() throws Exception {
        lookupFails();
        String preorderId = UUID.randomUUID().toString();
        PreorderStubs.stubPreorderNotFound(preorderClient, preorderId);

        perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content("""
                {"source":"PREORDER","preorderId":"%s",
                 "shipTo":{"name":"홍길동","phone":"010-0000-0000","postalCode":"04524","line1":"서울시 중구 세종대로 110"}}
                """.formatted(preorderId)), user)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PREORDER_NOT_FOUND"));
    }

    // ── 나머지 거부(11) ────────────────────────────────────────────────────────────

    @Test
    void unknownPathIsDeniedEvenWhenAuthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/unknown")).andExpect(status().isUnauthorized());
        perform(get("/api/v1/unknown"), user)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        perform(get("/actuator/health"), admin).andExpect(status().isForbidden());
    }

    @Test
    void userTokenOnAdminPathIsForbidden() throws Exception {
        perform(get("/api/v1/admin/orders"), user)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        perform(get("/api/v1/admin/orders"), admin).andExpect(status().isOk());
    }

    private ResultActions perform(MockHttpServletRequestBuilder request, String accessToken) throws Exception {
        return mockMvc.perform(request.header(BearerTokens.HEADER, BearerTokens.value(accessToken)));
    }

    private String token(String who) {
        return "admin".equals(who) ? admin : user;
    }

    private void lookupFails() {
        given(revocationChecker.isRevoked(any()))
                .willThrow(new RevocationCheckFailedException(new IllegalStateException("redis down")));
    }

    private static String cancelability() {
        return "/internal/orders/by-preorder/987654321/cancelability";
    }
}
