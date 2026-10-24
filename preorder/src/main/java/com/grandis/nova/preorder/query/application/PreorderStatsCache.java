package com.grandis.nova.preorder.query.application;

import com.grandis.nova.preorder.campaign.CampaignPhase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * 센 결과를 Redis 에 잠깐 둔다. 인스턴스가 여럿이어도 같은 값을 보고, 집계는 주기에 한 번쯤만 돈다.
 * Redis 를 쓸 수 없거나 값을 읽을 수 없으면 없는 것으로 본다 — 캐시 때문에 조회가 실패하지 않는다.
 */
@Component
class PreorderStatsCache {

    static final String KEY_PREFIX = "preorder:stats:";
    private static final String ALL = "all";
    private static final String PRODUCTS = "products:";
    private static final String FIRST_PAGE = "first";

    private static final Logger log = LoggerFactory.getLogger(PreorderStatsCache.class);

    private final StringRedisTemplate redis;
    private final JsonMapper jsonMapper;
    private final Duration ttl;

    PreorderStatsCache(StringRedisTemplate redis, JsonMapper jsonMapper, PreorderStatsProperties properties) {
        this.redis = redis;
        this.jsonMapper = jsonMapper;
        this.ttl = properties.cacheTtl();
    }

    /** @param productId null 이면 전체 */
    Optional<PreorderStats> find(UUID productId) {
        return read(key(productId), PreorderStats.class);
    }

    void put(UUID productId, PreorderStats stats) {
        write(key(productId), stats);
    }

    /** 상품별 현황 한 페이지. 같은 단계 · 커서 · 크기로 물은 것끼리 나눠 쓴다. */
    Optional<ProductStatsPage> findPage(CampaignPhase phase, String cursor, int size) {
        return read(pageKey(phase, cursor, size), ProductStatsPage.class);
    }

    void putPage(CampaignPhase phase, String cursor, int size, ProductStatsPage page) {
        write(pageKey(phase, cursor, size), page);
    }

    static String key(UUID productId) {
        return KEY_PREFIX + (productId == null ? ALL : productId);
    }

    /** 커서는 URL 안전 Base64 라 키에 그대로 넣는다. */
    static String pageKey(CampaignPhase phase, String cursor, int size) {
        return KEY_PREFIX + PRODUCTS + phase + ":" + size + ":" + (cursor == null ? FIRST_PAGE : cursor);
    }

    private <T> Optional<T> read(String key, Class<T> type) {
        if (ttl.isZero()) {
            return Optional.empty();
        }
        try {
            String cached = redis.opsForValue().get(key);
            return cached == null ? Optional.empty() : Optional.of(jsonMapper.readValue(cached, type));
        } catch (DataAccessException | JacksonException e) {
            log.warn("운영 현황 캐시를 읽지 못해 바로 센다: {}", e.toString());
            return Optional.empty();
        }
    }

    private void write(String key, Object value) {
        if (ttl.isZero()) {
            return;
        }
        try {
            redis.opsForValue().set(key, jsonMapper.writeValueAsString(value), ttl);
        } catch (DataAccessException | JacksonException e) {
            log.warn("운영 현황 캐시에 쓰지 못했다: {}", e.toString());
        }
    }
}
