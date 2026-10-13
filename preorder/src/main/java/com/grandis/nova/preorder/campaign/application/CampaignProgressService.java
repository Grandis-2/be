package com.grandis.nova.preorder.campaign.application;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.grandis.nova.common.BusinessException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.UUID;

/**
 * 상품의 접수 현황 공개 조회. 로그인 없이 많이 불리므로 인스턴스 안에 잠깐 둔다 — Redis 를 거치지 않아
 * 그 장애와 무관하게 답한다. 일정 · 차수만 두고 접수 상태는 물을 때의 시각으로 정해 오픈 · 마감 순간에도 맞는다.
 */
@Service
public class CampaignProgressService {

    private final CampaignProgressReader reader;
    /** 같은 상품을 동시에 물으면 한 번만 읽고 나머지는 그 결과를 기다린다. */
    private final Cache<UUID, CampaignProgressSource> cache;
    private final Clock clock;

    CampaignProgressService(CampaignProgressReader reader, CampaignProgressProperties properties, Clock clock) {
        this.reader = reader;
        this.clock = clock;
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(properties.cacheTtl())
                .maximumSize(properties.cacheMaxProducts())
                .build();
    }

    /** @throws BusinessException PRODUCT_NOT_FOUND — 회차가 없거나 아직 공개하지 않은 상품(캐시에 남기지 않는다) */
    public CampaignProgress find(UUID productId) {
        return cache.get(productId, reader::read).at(clock.instant());
    }
}
