package com.grandis.nova.common.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 액세스 토큰은 {@code Authorization: Bearer {accessToken}} 으로 온다(RFC 6750, 2026-09-28 결정 — 이전 {@code X-Session-Token}).
 * 헤더에서 토큰을 꺼내는 자리는 여기 하나다 — 인증 필터와 로그아웃이 같이 쓴다.
 *
 * <p>잘 갖춘 모양만 토큰이다: 스킴 {@code Bearer}(RFC 7235 대로 대소문자 무시) · 공백 하나 이상 · token68 문자만. 그 밖은 전부
 * "자격 증명 없음" 이다 — 헤더 없음 · 빈 값 · 다른 스킴(Basic …) · 스킴만 있음 · 토큰 뒤에 더 붙음 · 헤더가 둘 이상(어느 것인지 모호).
 * 없음으로 다루면 필터는 익명으로 넘기고, 보호 경로는 인가 규칙이 401 을 낸다. 잘못 붙인 헤더를 400 으로 가르지 않는 이유는
 * 다른 스킴의 헤더가 우리 것이 아닐 수 있어서다.
 */
public final class BearerTokens {

    public static final String HEADER = HttpHeaders.AUTHORIZATION;
    public static final String SCHEME = "Bearer";

    /** RFC 6750 b64token: 영숫자와 {@code -._~+/}, 끝의 {@code =} 패딩. JWT(base64url 세 조각과 '.')는 이 안에 든다. */
    private static final Pattern WELL_FORMED = Pattern.compile("^" + SCHEME + " +([A-Za-z0-9\\-._~+/]+=*)$", Pattern.CASE_INSENSITIVE);

    private BearerTokens() {
    }

    /** 요청의 액세스 토큰. 잘 갖춘 Bearer 헤더 하나일 때만 값이 있다. */
    public static Optional<String> extract(HttpServletRequest request) {
        List<String> headers = Collections.list(request.getHeaders(HEADER));
        if (headers.size() != 1) {
            return Optional.empty();
        }
        return parse(headers.getFirst());
    }

    /** 헤더 값 하나에서. 시험과 다른 진입점이 같은 규칙을 쓰게 공개한다. */
    public static Optional<String> parse(String headerValue) {
        if (headerValue == null) {
            return Optional.empty();
        }
        Matcher matcher = WELL_FORMED.matcher(headerValue.strip());
        return matcher.matches() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    /** 토큰을 헤더 값 모양으로 — 클라이언트 · 시험이 쓴다. */
    public static String value(String accessToken) {
        return SCHEME + " " + accessToken;
    }
}
