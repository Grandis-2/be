package com.grandis.nova.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 인증 필터. 순서는 헤더 → 파싱 → ACCESS 확인 → 폐기 조회 → 컨텍스트.
 *
 * 이 필터는 401 을 직접 내지 않는다. 인증에 실패하면 컨텍스트를 비운 채 다음으로 넘기고, 보호 경로면 entry point 가 401 을 낸다.
 * 그래서 공개 경로에 잘못된 토큰이 와도 통과한다 — 401 은 인가 규칙이 보호 경로에서만 낸다. 실패 이유는 요청 속성과 WARN 로그에만 남는다.
 *
 * 빈이 아니다. SecurityFilterChainSupport 가 체인 안에서 만든다 — 빈으로 두면 Boot 가 서블릿 필터로 한 번 더 등록한다.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Session-Token";
    /** 인증 실패 이유. entry point 가 읽어 봉투에 넣지 않고 로그 상관용으로만 둔다. */
    public static final String ATTR_FAILURE_REASON = JwtAuthenticationFilter.class.getName() + ".reason";
    /** 폐기 조회 실패로 닫힌 경우 true. entry point 가 봉투의 error.details.retryable 을 true 로 낸다. */
    public static final String ATTR_RETRYABLE = JwtAuthenticationFilter.class.getName() + ".retryable";
    static final String MDC_SUBJECT = "subject";

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private final JwtTokenProvider provider;
    private final RevocationChecker revocationChecker;
    private final RevocationFailurePolicy policy;

    public JwtAuthenticationFilter(JwtTokenProvider provider, RevocationChecker revocationChecker, RevocationFailurePolicy policy) {
        this.provider = provider;
        this.revocationChecker = revocationChecker;
        this.policy = policy;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = request.getHeader(HEADER);
        if (token == null || token.isBlank() || SecurityContextHolder.getContext().getAuthentication() != null) {
            chain.doFilter(request, response);
            return;
        }

        TokenClaims claims;
        try {
            claims = provider.parse(token);
        } catch (InvalidTokenException e) {
            reject(request, e.reason(), false);
            chain.doFilter(request, response);
            return;
        }
        if (claims.type() != TokenType.ACCESS) {
            reject(request, "not an access token: " + claims.type(), false);
            chain.doFilter(request, response);
            return;
        }

        // try 는 isRevoked 한 줄만 감싼다. chain.doFilter 를 안에 두면 하류에서 올라온 같은 예외를 잡아 체인을 두 번 부른다.
        Boolean revoked;
        try {
            revoked = revocationChecker.isRevoked(claims);
        } catch (RevocationCheckFailedException e) {
            revoked = null;
            if (policy.failClosed(request)) {
                // 닫는 경로. 폐기 여부를 모르면 거부하되 retryable 로 표시한다.
                reject(request, "revocation lookup failed on fail-closed path: " + e.getMessage(), true);
                chain.doFilter(request, response);
                return;
            }
            long count = policy.recordFallbackOpen();
            log.warn("auth.revocation.fallback_open count={} path={} sid={} cause={}",
                    count, request.getRequestURI(), shortSid(claims), e.getMessage());
        }
        if (Boolean.TRUE.equals(revoked)) {
            reject(request, "revoked sid=" + shortSid(claims) + " (or nbf on auth:nbf:" + claims.subject() + ")", false);
            chain.doFilter(request, response);
            return;
        }

        SecurityContextHolder.getContext().setAuthentication(
                new NovaAuthentication(new AuthenticatedPrincipal(claims.subject(), claims.role())));
        MDC.put(MDC_SUBJECT, claims.subject());
        try {
            chain.doFilter(request, response);
        } finally {
            // 공통 RequestIdFilter 는 traceId 만 지운다. 스레드가 풀로 돌아가므로 subject 는 여기서 지운다.
            MDC.remove(MDC_SUBJECT);
        }
    }

    private static void reject(HttpServletRequest request, String reason, boolean retryable) {
        SecurityContextHolder.clearContext();
        request.setAttribute(ATTR_FAILURE_REASON, reason);
        if (retryable) {
            request.setAttribute(ATTR_RETRYABLE, Boolean.TRUE);
        }
        log.warn("auth rejected path={} reason={}", request.getRequestURI(), reason);
    }

    private static String shortSid(TokenClaims claims) {
        return claims.sessionId().toString().substring(0, 8);
    }
}
