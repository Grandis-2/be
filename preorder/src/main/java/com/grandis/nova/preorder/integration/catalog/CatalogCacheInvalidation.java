package com.grandis.nova.preorder.integration.catalog;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 상품 캐시 비우기를 모든 인스턴스에 알린다. 캐시는 인스턴스마다 따로인데 큐 메시지는 한 대만 받으므로,
 * 받은 인스턴스가 Redis 채널로 알리고 모든 인스턴스가 구독해 비운다. 알리지 못하면 나머지는 1분 갱신으로 따라온다.
 */
@Component
public class CatalogCacheInvalidation {

    static final String CHANNEL = "preorder:catalog-evict";

    private static final Logger log = LoggerFactory.getLogger(CatalogCacheInvalidation.class);

    private final CatalogReader catalogReader;
    private final StringRedisTemplate redis;

    CatalogCacheInvalidation(CatalogReader catalogReader, StringRedisTemplate redis) {
        this.catalogReader = catalogReader;
        this.redis = redis;
    }

    /** 이 인스턴스는 바로 비우고 나머지에 알린다. 알리기 실패는 이벤트 처리를 실패시키지 않는다. */
    public void evictEverywhere(Long productId) {
        catalogReader.evict(productId);
        try {
            redis.convertAndSend(CHANNEL, productId.toString());
        } catch (RuntimeException e) {
            log.warn("상품 캐시 비우기를 다른 인스턴스에 알리지 못했다 — 1분 갱신으로 따라온다 productId={}: {}",
                    productId, e.toString());
        }
    }

    /** 다른 인스턴스(또는 자신)가 보낸 알림. 읽을 수 없는 본문은 버린다 — 다음 갱신이 바로잡는다. */
    void onMessage(String body) {
        try {
            catalogReader.evict(Long.valueOf(body));
        } catch (NumberFormatException e) {
            log.warn("읽을 수 없는 상품 캐시 비우기 알림을 버린다: {}", body);
        }
    }
}
