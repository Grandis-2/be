package com.grandis.nova.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 필터 한 장만 본다. 체인·401 봉투·경로 규칙은 SecurityChainTest.
 * 폐기 조회는 모킹한다 — 세 가지 답(false / true / 예외)이 필터에서 어떻게 갈리는지가 대상이다.
 */
@DisplayName("JwtAuthenticationFilter")
class JwtAuthenticationFilterTest {

    private static final Instant NOW = Instant.parse("2026-09-19T12:00:00Z");

    private JwtTokenProvider provider;
    private RevocationChecker checker;
    private RevocationFailurePolicy policy;
    private JwtAuthenticationFilter filter;
    private final UUID sid = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        provider = TestKeys.issuer("nova-test", TestKeys.ISSUER, TestKeys.KID, Duration.ofHours(1), Duration.ofDays(14), Clock.fixed(NOW, ZoneOffset.UTC));
        checker = mock(RevocationChecker.class);
        policy = new RevocationFailurePolicy(new RevocationCheckProperties(null));   // 닫는 경로 기본 목록
        filter = new JwtAuthenticationFilter(provider, checker, policy);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        MDC.clear();
    }

    private MockHttpServletRequest request(String path, String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setServletPath(path);
        if (token != null) {
            request.addHeader(JwtAuthenticationFilter.HEADER, token);
        }
        return request;
    }

    private Authentication run(MockHttpServletRequest request) throws Exception {
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        return SecurityContextHolder.getContext().getAuthentication();
    }

    private String access(String subject, Role role) {
        return provider.create(subject, role, sid, TokenType.ACCESS);
    }

    @Test
    @DisplayName("헤더가 없으면 아무것도 하지 않고 다음으로 넘긴다 — 폐기 조회도 안 한다")
    void noHeaderPassesThrough() throws Exception {
        assertThat(run(request("/api/v1/reservations", null))).isNull();
        verifyNoInteractions(checker);
    }

    @Test
    @DisplayName("잘못된 토큰은 컨텍스트를 비운 채 넘긴다. 401 은 여기서 내지 않는다")
    void malformedTokenIsRejectedSilently() throws Exception {
        MockHttpServletRequest request = request("/api/v1/reservations", "not.a.jwt");

        assertThat(run(request)).isNull();
        assertThat(request.getAttribute(JwtAuthenticationFilter.ATTR_FAILURE_REASON)).isNotNull();
        assertThat(request.getAttribute(JwtAuthenticationFilter.ATTR_RETRYABLE)).isNull();
        verifyNoInteractions(checker);
    }

    @Test
    @DisplayName("REFRESH 토큰으로는 인증하지 않는다 (타입 오용)")
    void refreshTokenIsRejected() throws Exception {
        String refresh = provider.create("101", Role.USER, sid, TokenType.REFRESH);

        assertThat(run(request("/api/v1/reservations", refresh))).isNull();
        verifyNoInteractions(checker);
    }

    @Test
    @DisplayName("폐기된 토큰은 인증하지 않는다")
    void revokedTokenIsRejected() throws Exception {
        when(checker.isRevoked(any())).thenReturn(true);

        assertThat(run(request("/api/v1/reservations", access("101", Role.USER)))).isNull();
    }

    @Test
    @DisplayName("정상 USER 토큰은 ROLE_USER 인증 객체를 넣고, 하류가 도는 동안만 MDC 에 subject 를 둔다(끝나면 지운다 — 공통 RequestIdFilter 는 traceId 만 지운다)")
    void validUserTokenAuthenticates() throws Exception {
        when(checker.isRevoked(any())).thenReturn(false);
        String[] subjectSeenDownstream = new String[1];
        jakarta.servlet.FilterChain capturing = (req, res) -> subjectSeenDownstream[0] = MDC.get(JwtAuthenticationFilter.MDC_SUBJECT);

        filter.doFilter(request("/api/v1/reservations", access("101", Role.USER)), new MockHttpServletResponse(), capturing);
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        assertThat(auth).isInstanceOf(NovaAuthentication.class);
        assertThat(auth.getName()).isEqualTo("101");
        assertThat(auth.getAuthorities()).extracting("authority").containsExactly("ROLE_USER");
        assertThat(((NovaAuthentication) auth).getPrincipal().customerId()).isEqualTo(101L);
        assertThat(subjectSeenDownstream[0]).isEqualTo("101");
        assertThat(MDC.get(JwtAuthenticationFilter.MDC_SUBJECT)).as("스레드 풀 재사용 시 다음 요청으로 새지 않게 지운다").isNull();
        verify(checker).isRevoked(any());
    }

    @Test
    @DisplayName("ADMIN 토큰은 ROLE_ADMIN 이고 subject 는 admin 이다")
    void adminTokenAuthenticates() throws Exception {
        when(checker.isRevoked(any())).thenReturn(false);

        Authentication auth = run(request("/api/v1/admin/stats", access("admin", Role.ADMIN)));

        assertThat(auth.getAuthorities()).extracting("authority").containsExactly("ROLE_ADMIN");
        assertThat(auth.getName()).isEqualTo("admin");
    }

    @Test
    @DisplayName("폐기 조회 실패 + 열린 경로(접수) → 통과시키고 fallback_open 을 센다")
    void lookupFailureOnOpenPathPasses() throws Exception {
        when(checker.isRevoked(any())).thenThrow(new RevocationCheckFailedException(new RuntimeException("redis down")));

        Authentication auth = run(request("/api/v1/reservations", access("101", Role.USER)));

        assertThat(auth).isNotNull();
        assertThat(policy.fallbackOpenCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("폐기 조회 실패 + 닫힌 경로(재발급) → 인증하지 않고 retryable 표시를 남긴다")
    void lookupFailureOnClosedPathRejects() throws Exception {
        when(checker.isRevoked(any())).thenThrow(new RevocationCheckFailedException(new RuntimeException("redis down")));
        MockHttpServletRequest request = request("/api/v1/session/refresh", access("101", Role.USER));

        assertThat(run(request)).isNull();
        assertThat(request.getAttribute(JwtAuthenticationFilter.ATTR_RETRYABLE)).isEqualTo(Boolean.TRUE);
        assertThat(policy.fallbackOpenCount()).isZero();
    }

    @Test
    @DisplayName("조회 실패가 아닌 예상 못 한 예외는 뭉치지 않고 그대로 터진다 (조용한 통과보다 500 이 낫다)")
    void unexpectedExceptionPropagates() {
        when(checker.isRevoked(any())).thenThrow(new IllegalStateException("bug"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> run(request("/api/v1/reservations", access("101", Role.USER))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("이미 인증 객체가 있으면 덮어쓰지 않는다")
    void existingAuthenticationIsKept() throws Exception {
        NovaAuthentication existing = new NovaAuthentication(new AuthenticatedPrincipal("7", Role.USER));
        SecurityContextHolder.getContext().setAuthentication(existing);

        Authentication auth = run(request("/api/v1/reservations", access("101", Role.USER)));

        assertThat(auth).isSameAs(existing);
        verifyNoInteractions(checker);
    }
}
