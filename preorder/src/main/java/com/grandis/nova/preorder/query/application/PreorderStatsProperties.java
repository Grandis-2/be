package com.grandis.nova.preorder.query.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * @param cacheTtl 센 결과를 Redis 에 두는 시간. 화면이 여럿 열려 있어도 집계는 이 주기에 한 번쯤만 돈다 —
 *                 5초면 관리자가 조치한 뒤 다음 갱신 한두 번 안에 숫자가 바뀐다. 0 이면 캐시 없이 매번 센다
 * @param productPageSize    상품별 현황 한 페이지의 기본 상품 수. 20 이면 접수 중 상품은 보통 한 페이지에 다 든다
 * @param productPageMaxSize 한 페이지 상한. 상품마다 집계 문장이 커지므로 다른 관리자 목록 상한(100)과 맞춘다
 */
@ConfigurationProperties("nova.preorder-stats")
record PreorderStatsProperties(
        @DefaultValue("5s") Duration cacheTtl,
        @DefaultValue("20") int productPageSize,
        @DefaultValue("100") int productPageMaxSize
) {
    PreorderStatsProperties {
        if (cacheTtl.isNegative()) {
            throw new IllegalArgumentException("nova.preorder-stats.cache-ttl 은 0 이상이어야 한다");
        }
        if (productPageSize < 1 || productPageSize > productPageMaxSize) {
            throw new IllegalArgumentException("nova.preorder-stats.product-page-size 는 1 이상 product-page-max-size("
                    + productPageMaxSize + ") 이하여야 한다: " + productPageSize);
        }
    }
}
