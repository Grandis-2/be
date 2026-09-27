package com.grandis.nova.common.security.chain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.grandis.nova.common.security.JwtAuthenticationFilter;
import com.grandis.nova.common.security.JwtTokenProvider;
import com.grandis.nova.common.security.RevocationCheckFailedException;
import com.grandis.nova.common.security.RevocationChecker;
import com.grandis.nova.common.security.Role;
import com.grandis.nova.common.security.TokenType;
import com.grandis.nova.common.web.RequestIdFilter;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * 필터 체인 확인 항목 + 폐기 조회 실패 경로 정책 + 401/403 봉투. 필터 체인 전체를 MockMvc 로 태운다.
 * Redis 는 없다 — RevocationChecker 를 모킹해 세 가지 답을 만든다. 두 실측(PathPattern 매칭, @CurrentCustomerId 의 ADMIN 403 경로)이 여기 있다.
 */
@SpringBootTest(classes = ChainTestApp.class, properties = {
        "jwt.issuer=nova-test",
        "jwt.access-token-validity=1h",
        "jwt.refresh-token-validity=14d"
})
@DisplayName("SecurityFilterChain (인가 규칙 · 폐기 조회 실패 정책 · 응답 봉투)")
class SecurityChainTest {

    @org.springframework.test.context.DynamicPropertySource
    static void keys(org.springframework.test.context.DynamicPropertyRegistry registry) {
        com.grandis.nova.common.security.TestKeys.register(registry);
    }

    @Autowired WebApplicationContext context;
    @Autowired JwtTokenProvider provider;
    @Autowired FilterChainProxy springSecurityFilterChain;
    @Autowired RequestIdFilter requestIdFilter;
    @MockitoBean RevocationChecker checker;

    private MockMvc mvc;
    private String user;
    private String admin;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(requestIdFilter, springSecurityFilterChain)   // 서블릿 필터 순서 그대로: traceId → 보안 체인
                .build();
        UUID sid = UUID.randomUUID();
        user = provider.create("101", Role.USER, sid, TokenType.ACCESS);
        admin = provider.create("admin", Role.ADMIN, UUID.randomUUID(), TokenType.ACCESS);
        when(checker.isRevoked(any())).thenReturn(false);
    }

    private static final String H = JwtAuthenticationFilter.HEADER;

    @Test
    @DisplayName("토큰 없이 공개 경로는 200")
    void publicWithoutToken() throws Exception {
        mvc.perform(get("/api/v1/products/1")).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("토큰 없이 보호 경로는 401 UNAUTHENTICATED 봉투 (traceId 포함, details 없음)")
    void protectedWithoutToken() throws Exception {
        mvc.perform(post("/api/v1/reservations"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists(RequestIdFilter.HEADER))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.error.details").doesNotExist())
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    @DisplayName("잘못된 토큰은 401")
    void malformedToken() throws Exception {
        mvc.perform(post("/api/v1/reservations").header(H, "garbage")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("공개 경로에 잘못된 토큰이 와도 통과한다 (필터는 401 을 직접 내지 않는다)")
    void publicWithGarbageToken() throws Exception {
        mvc.perform(get("/api/v1/products/1").header(H, "garbage")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("폐기된 토큰은 401")
    void revokedToken() throws Exception {
        when(checker.isRevoked(any())).thenReturn(true);
        mvc.perform(post("/api/v1/reservations").header(H, user)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("USER 토큰으로 /me 는 200 이고 @CurrentCustomerId 가 101")
    void userMe() throws Exception {
        mvc.perform(get("/api/v1/me").header(H, user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customerId").value(101));
    }

    @Test
    @DisplayName("실측: ADMIN 토큰으로 사용자 API(/me) 는 403 FORBIDDEN 봉투 — 리졸버의 BusinessException 이 GlobalExceptionHandler 로 간다")
    void adminTokenOnCustomerEndpointIs403() throws Exception {
        mvc.perform(get("/api/v1/me").header(H, admin))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("USER 토큰인데 sub 가 십진수가 아니면 /me 는 500 이 아니라 401")
    void userTokenWithNonNumericSubject() throws Exception {
        String odd = provider.create("abc", Role.USER, UUID.randomUUID(), TokenType.ACCESS);
        mvc.perform(get("/api/v1/me").header(H, odd))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("USER 토큰으로 /admin/** 은 403 FORBIDDEN 봉투 (hasRole 이 ROLE_ADMIN 을 읽는다)")
    void userOnAdminIs403() throws Exception {
        mvc.perform(get("/api/v1/admin/stats").header(H, user))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("ADMIN 토큰으로 /admin/** 은 200")
    void adminOnAdminIs200() throws Exception {
        mvc.perform(get("/api/v1/admin/stats").header(H, admin)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("폐기 조회 실패 + 접수(열린 경로) → 200")
    void lookupFailureOnIntakePasses() throws Exception {
        when(checker.isRevoked(any())).thenThrow(new RevocationCheckFailedException(new RuntimeException("down")));
        mvc.perform(post("/api/v1/reservations").header(H, user)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("재발급 경로에 액세스 토큰을 들고 오고 Redis 가 죽어 있으면 필터는 인증을 안 하지만 공개 경로라 200 — 진짜 방어는 TokenService.rotate 의 폐기 검사")
    void lookupFailureOnRefreshWithAccessTokenStillReachesController() throws Exception {
        when(checker.isRevoked(any())).thenThrow(new RevocationCheckFailedException(new RuntimeException("down")));
        var result = mvc.perform(post("/api/v1/session/refresh").header(H, user)).andExpect(status().isOk()).andReturn();
        // 필터의 닫힌 경로 판정은 여기서도 돌았다(요청 속성). 401 로 이어지지 않는 것은 permitAll 이 먼저라서다.
        assertThat(result.getRequest().getAttribute(JwtAuthenticationFilter.ATTR_RETRYABLE)).isEqualTo(Boolean.TRUE);
    }

    @Test
    @DisplayName("재발급은 공개다 — 토큰 없이(쿠키만) 쳐도 인가 단계는 통과한다. 폐기 검사는 이 경로 매칭이 아니라 TokenService.rotate 가 리프레시 클레임으로 한다")
    void refreshIsPublicForCookieOnlyRequests() throws Exception {
        // 실제 클라이언트 요청 모양(api-spec 3849행): X-Session-Token 없음, Cookie 만. 이때 필터는 헤더가 없어 아무것도 안 한다.
        when(checker.isRevoked(any())).thenThrow(new RevocationCheckFailedException(new RuntimeException("down")));
        mvc.perform(post("/api/v1/session/refresh").cookie(new jakarta.servlet.http.Cookie("refresh_token", "x")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("취소 경로 둘(reservations/*/cancel, orders/*/cancel)은 닫힌다")
    void lookupFailureOnCancelPathsIsClosed() throws Exception {
        when(checker.isRevoked(any())).thenThrow(new RevocationCheckFailedException(new RuntimeException("down")));
        mvc.perform(post("/api/v1/reservations/abc/cancel").header(H, user)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/orders/abc/cancel").header(H, user)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("/api/v1/me/** 는 개인정보라 닫힌다 — 조회 실패 시 401 details.retryable=true. ** 가 0 세그먼트도 먹어 /api/v1/me 자체도")
    void lookupFailureOnMeIsClosed() throws Exception {
        when(checker.isRevoked(any())).thenThrow(new RevocationCheckFailedException(new RuntimeException("down")));
        mvc.perform(get("/api/v1/me").header(H, user))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.details.retryable").value(true));
    }

    @Test
    @DisplayName("실측: `/api/v1/orders/*/payment-attempts/**` 는 0 세그먼트도 먹어 결제창 초기화(POST …/payment-attempts)도 닫힌다")
    void lookupFailureOnPaymentInitIsClosed() throws Exception {
        when(checker.isRevoked(any())).thenThrow(new RevocationCheckFailedException(new RuntimeException("down")));
        mvc.perform(post("/api/v1/orders/1/payment-attempts").header(H, user)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("실측: 본문이 깨진 JSON 은 400 VALIDATION_FAILED 봉투다 (MVC 기본 오류가 500 으로 새지 않는다)")
    void malformedJsonIs400Envelope() throws Exception {
        mvc.perform(post("/api/v1/reservations").header(H, user)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("실측: 없는 URL 은 404 NOT_FOUND 봉투다")
    void unknownUrlIs404Envelope() throws Exception {
        mvc.perform(get("/api/v1/nope").header(H, user))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("실측: 지원하지 않는 메서드는 405 이고 봉투다")
    void wrongMethodIs405Envelope() throws Exception {
        mvc.perform(post("/api/v1/me").header(H, user))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    @DisplayName("실측: PathPattern `/api/v1/orders/*/payment-attempts/**` 가 confirm 경로에 걸려 닫힌다")
    void lookupFailureOnPaymentConfirmIsClosed() throws Exception {
        when(checker.isRevoked(any())).thenThrow(new RevocationCheckFailedException(new RuntimeException("down")));
        mvc.perform(post("/api/v1/orders/1/payment-attempts/9/confirm").header(H, user))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.details.retryable").value(true));
    }

    @Test
    @DisplayName("폐기 조회 실패 + ADMIN 이 /admin/** (닫힌 경로) → 401")
    void lookupFailureOnAdminIsClosed() throws Exception {
        when(checker.isRevoked(any())).thenThrow(new RevocationCheckFailedException(new RuntimeException("down")));
        mvc.perform(get("/api/v1/admin/stats").header(H, admin)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("실측: 공개 경로 POST /admin/session 에 토큰을 들고 오고 Redis 가 죽어 있어도 200 — 닫힌 패턴에 겹치지만 permitAll 이 먼저다")
    void adminLoginIsPublicEvenWhenLookupFails() throws Exception {
        when(checker.isRevoked(any())).thenThrow(new RevocationCheckFailedException(new RuntimeException("down")));
        mvc.perform(post("/api/v1/admin/session").header(H, user)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/admin/session")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("들어온 X-Request-Id 는 그대로 봉투와 응답 헤더에 실린다")
    void traceIdIsEchoed() throws Exception {
        mvc.perform(post("/api/v1/reservations").header(RequestIdFilter.HEADER, "trace-abc"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(RequestIdFilter.HEADER, "trace-abc"))
                .andExpect(jsonPath("$.traceId").value("trace-abc"));
    }

    @Test
    @DisplayName("세션 쿠키를 만들지 않는다 (STATELESS)")
    void noSessionCookie() throws Exception {
        var result = mvc.perform(get("/api/v1/me").header(H, user)).andExpect(status().isOk()).andReturn();
        assertThat(result.getResponse().getCookies()).isEmpty();
        assertThat(result.getRequest().getSession(false)).isNull();
    }

    @Test
    @DisplayName("실측: Spring Security 기본 보안 헤더가 그대로 붙는다(headers 를 안 껐다) — 401 응답에도")
    void defaultSecurityHeadersArePresent() throws Exception {
        mvc.perform(get("/api/v1/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Cache-Control", "no-cache, no-store, max-age=0, must-revalidate"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"));
        // HSTS 는 HTTPS 응답에만 붙는다(Spring Security 문서, RFC 6797). MockMvc 는 http 라 없어야 정상 — CloudFront/ALB 뒤 TLS 종료 환경은 이식 때 확인
    }
}
