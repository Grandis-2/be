package com.grandis.nova.catalog.api;

import com.grandis.nova.common.redis.cache.ReadCache;
import com.grandis.nova.common.web.ApiResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;

import java.time.Clock;
import java.time.Duration;

/**
 * 로그인 없이 보는 공개 조회 응답의 Cache-Control. 받은 쪽(브라우저)이 이 시간만큼 들고 있어도 된다는 뜻이다 — 로그인과 상관없이 같은 응답이라
 * public 이다. API 앞의 CloudFront 는 캐시하지 않는다(nova-infra edge 의 Managed-CachingDisabled). 다른 응답(관리자 · 오류)은 Spring Security 기본값(no-store)이 그대로 붙는다.
 * max-age 는 그 값을 DB 에서 읽은 뒤 읽기 캐시 TTL 에서 남은 시간과 상한 중 작은 쪽이다 — 읽기 캐시(Redis · 메모리)에서 4.9초 묵은 값에 5초를 더 주면 합쳐서
 * 10초 가까이 옛 값이 보인다. 시각으로 정하는 칸(접수 단계)도 앞단이 들고 있는 동안은 다시 계산되지 않으므로 같은 상한 안에서 늦을 수 있다.
 */
final class PublicCache {

    /** 상한 — 상품 · 리뷰는 읽기 캐시 TTL 과 같고, 카테고리는 TTL(10분)보다 짧게 둔다. */
    static final Duration PRODUCT = Duration.ofSeconds(5);
    static final Duration REVIEW = Duration.ofSeconds(60);
    static final Duration CATEGORY = Duration.ofMinutes(5);

    private PublicCache() {
    }

    static <T> ResponseEntity<ApiResponse<T>> ok(ReadCache.Entry<T> entry, Duration ttl, Duration cap, Clock clock) {
        return ok(entry.value(), entry.remaining(ttl, clock.instant()), cap);
    }

    /** 남은 시간을 호출자가 정한다 — 나이가 다른 값(홍보 본문 · 카드)을 합친 응답은 그중 짧은 쪽을 넘긴다. */
    static <T> ResponseEntity<ApiResponse<T>> ok(T value, Duration remaining, Duration cap) {
        long seconds = Math.min(cap.toSeconds(), remaining.toSeconds());
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofSeconds(seconds)).cachePublic())
                .body(ApiResponse.ok(value));
    }
}
