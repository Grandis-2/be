package com.grandis.nova.preorder.campaign.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * @param ttl                회차를 인스턴스 안에 두는 시간. 비우기(커밋 뒤 · 모든 인스턴스)가 정상 경로이고 이 값은 비우기를 놓쳤을 때
 *                           옛 값이 보이는 상한이다. 30초면 상품 화면이 몰려도 상품마다 30초에 한 번만 읽는다
 * @param maxProducts        몇 개 상품까지 둘지. 사전예약 회차 수를 넘지 않으므로 1,000 이면 넉넉하다
 * @param missingTtl         "회차 없음" 을 두는 시간. 회차를 만들면 비우므로 짧게 둔다 — 놓쳐도 5초 뒤 보인다
 * @param missingMaxProducts "회차 없음" 을 몇 개까지 둘지. 따로 두어 무작위 id 가 진짜 회차를 밀어내지 못하게 한다
 */
@ConfigurationProperties("nova.campaign.cache")
record CampaignCacheProperties(
        @DefaultValue("30s") Duration ttl,
        @DefaultValue("1000") int maxProducts,
        @DefaultValue("5s") Duration missingTtl,
        @DefaultValue("10000") int missingMaxProducts
) {
    CampaignCacheProperties {
        if (!ttl.isPositive() || !missingTtl.isPositive()) {
            throw new IllegalArgumentException("nova.campaign.cache.ttl · missing-ttl 은 0 보다 커야 한다");
        }
        if (maxProducts < 1 || missingMaxProducts < 1) {
            throw new IllegalArgumentException("nova.campaign.cache.max-products · missing-max-products 는 1 이상이어야 한다");
        }
    }
}
