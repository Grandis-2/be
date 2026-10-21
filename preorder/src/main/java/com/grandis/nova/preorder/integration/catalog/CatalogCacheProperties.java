package com.grandis.nova.preorder.integration.catalog;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * 접수용 catalog 상품 캐시({@link CatalogReader}).
 *
 * @param refreshAfter   이보다 묵은 값은 그대로 돌려주고 뒤에서 다시 받는다
 * @param maxStale       이보다 묵은 값은 그 요청에서 다시 받는다 — 비우기 알림을 놓친 인스턴스가 옛 값으로 판정하는 상한.
 *                       5분: 판매 중지 · 수정이 접수에 닿는 지연으로 받아들일 수 있는 크기이고, 오픈 순간의 몰림은 1분 다시 받기가 막는다
 * @param refreshWait    그 요청에서 다시 받을 때 기다리는 상한. 넘으면 가진 값을 주고 받기는 뒤에서 마저 한다 — 같은 상품에 몰린 접수가
 *                       catalog 호출 시간 초과만큼 함께 묶이지 않게. 1초: 접수 응답에 더해도 되는 지연
 * @param failureBackoff 그 요청에서 다시 받기가 실패한 상품은 이만큼 다시 묻지 않고 가진 값을 준다 — catalog 장애 때 요청마다
 *                       호출 시간 초과를 기다리지 않게(차단기가 열리기 전 구간)
 * @param expireAfter    받은 지 이만큼 지나면 버린다. 그 뒤 첫 조회는 catalog 가 응답해야 한다(못 하면 503)
 */
@ConfigurationProperties("nova.catalog.cache")
record CatalogCacheProperties(
        @DefaultValue("1m") Duration refreshAfter,
        @DefaultValue("5m") Duration maxStale,
        @DefaultValue("1s") Duration refreshWait,
        @DefaultValue("10s") Duration failureBackoff,
        @DefaultValue("30m") Duration expireAfter
) {
    CatalogCacheProperties {
        if (!refreshAfter.isPositive() || !refreshWait.isPositive() || !failureBackoff.isPositive()
                || maxStale.compareTo(refreshAfter) <= 0 || expireAfter.compareTo(maxStale) <= 0) {
            throw new IllegalArgumentException(
                    "nova.catalog.cache 는 0 < refresh-after < max-stale < expire-after, refresh-wait · failure-backoff > 0 이어야 한다");
        }
    }

    static CatalogCacheProperties defaults() {
        return new CatalogCacheProperties(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofSeconds(1),
                Duration.ofSeconds(10), Duration.ofMinutes(30));
    }
}
