package com.grandis.nova.common.cors;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.cors.CorsConfiguration;

import java.time.Duration;
import java.util.List;

/**
 * 서블릿 · WebFlux 가 같이 쓰는 CORS 규칙 하나(모든 경로). 헤더는 API 가 받고(요청) · 프론트가 읽는(응답) 것만 연다.
 * 자격 증명을 허용한다 — 재발급 · 로그아웃이 API 도메인의 리프레시 쿠키로 동작한다(프론트는 credentials: 'include').
 * 재발급 허용 오리진(member 의 auth.refresh.allowed-origins)도 더한다 — CORS 에 없으면 그 오리진의 재발급을 브라우저가 막는다.
 */
final class CorsRules {

    static final List<String> METHODS = List.of(
            HttpMethod.GET.name(), HttpMethod.POST.name(), HttpMethod.PUT.name(), HttpMethod.PATCH.name(),
            HttpMethod.DELETE.name());
    static final List<String> REQUEST_HEADERS = List.of(
            HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE, "Idempotency-Key", "X-Admission-Ticket", "Queue-Token",
            "X-Request-Id");
    static final List<String> RESPONSE_HEADERS = List.of(
            HttpHeaders.LOCATION, HttpHeaders.RETRY_AFTER, "X-Idempotent-Replay", "X-Request-Id");
    /** 사전 요청(OPTIONS) 결과를 브라우저가 묵혀 두는 시간. 크롬은 2시간에서 자른다. */
    static final Duration PREFLIGHT_CACHE = Duration.ofHours(1);
    static final String REFRESH_ORIGINS = "auth.refresh.allowed-origins";

    private CorsRules() {
    }

    static CorsConfiguration configuration(CorsProperties properties, Environment environment) {
        List<String> refreshOrigins = Binder.get(environment)
                .bind(REFRESH_ORIGINS, Bindable.listOf(String.class)).orElse(List.of());
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(properties.including(refreshOrigins).allowedOrigins());
        configuration.setAllowedMethods(METHODS);
        configuration.setAllowedHeaders(REQUEST_HEADERS);
        configuration.setExposedHeaders(RESPONSE_HEADERS);
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(PREFLIGHT_CACHE);
        return configuration;
    }
}
