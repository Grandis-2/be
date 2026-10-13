package com.grandis.nova.preorder.campaign.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * @param cacheTtl         접수 현황을 인스턴스 안에 두는 시간. 오픈 직후 상품 화면이 몰려도 DB 는 상품마다 이 주기에 한 번만 읽는다 —
 *                         다음 차수는 구간을 넘을 때만 바뀌므로 5초 늦어도 안내가 달라지지 않는다. 0 이면 매번 읽는다
 * @param cacheMaxProducts 몇 개 상품까지 둘지. 사전 예약 상품 수를 넘지 않으므로 200 이면 넉넉하다
 */
@ConfigurationProperties("nova.campaign.progress")
record CampaignProgressProperties(
        @DefaultValue("5s") Duration cacheTtl,
        @DefaultValue("200") int cacheMaxProducts
) {
    CampaignProgressProperties {
        if (cacheTtl.isNegative()) {
            throw new IllegalArgumentException("nova.campaign.progress.cache-ttl 은 0 이상이어야 한다");
        }
        if (cacheMaxProducts < 1) {
            throw new IllegalArgumentException("nova.campaign.progress.cache-max-products 는 1 이상이어야 한다");
        }
    }
}
