package com.grandis.nova.preorder.query.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * @param cacheTtl 센 결과를 Redis 에 두는 시간. 화면이 여럿 열려 있어도 집계는 이 주기에 한 번쯤만 돈다 —
 *                 5초면 관리자가 조치한 뒤 다음 갱신 한두 번 안에 숫자가 바뀐다. 0 이면 캐시 없이 매번 센다
 */
@ConfigurationProperties("nova.preorder-stats")
record PreorderStatsProperties(
        @DefaultValue("5s") Duration cacheTtl
) {
    PreorderStatsProperties {
        if (cacheTtl.isNegative()) {
            throw new IllegalArgumentException("nova.preorder-stats.cache-ttl 은 0 이상이어야 한다");
        }
    }
}
