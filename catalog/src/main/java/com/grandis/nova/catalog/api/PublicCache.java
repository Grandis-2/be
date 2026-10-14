package com.grandis.nova.catalog.api;

import com.grandis.nova.common.web.ApiResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;

import java.time.Duration;

/**
 * 로그인 없이 보는 공개 조회 응답의 Cache-Control. 앞단 CDN(CloudFront)이 이 시간만큼 들고 있어도 된다는 뜻이다 — 로그인과 상관없이 같은 응답이라
 * public 이다. 다른 응답은 Spring Security 기본값(no-store)이 그대로 붙는다.
 * 시간은 읽기 캐시 TTL 과 같거나 짧게 둔다 — CDN 캐시는 be 가 비울 수 없어 관리자 수정이 이 시간만큼 늦게 보인다.
 */
final class PublicCache {

    static final Duration PRODUCT = Duration.ofSeconds(5);
    static final Duration REVIEW = Duration.ofSeconds(60);
    static final Duration CATEGORY = Duration.ofMinutes(5);

    private PublicCache() {
    }

    static <T> ResponseEntity<ApiResponse<T>> ok(Duration maxAge, T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(maxAge).cachePublic()).body(ApiResponse.ok(body));
    }
}
