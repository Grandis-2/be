package com.grandis.nova.preorder.query.application;

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
        if (ttl.isZero()) {
            return Optional.empty();
        }
        try {
            String cached = redis.opsForValue().get(key(productId));
            return cached == null ? Optional.empty() : Optional.of(jsonMapper.readValue(cached, PreorderStats.class));
        } catch (DataAccessException | JacksonException e) {
            log.warn("운영 현황 캐시를 읽지 못해 바로 센다: {}", e.toString());
            return Optional.empty();
        }
    }

    void put(UUID productId, PreorderStats stats) {
        if (ttl.isZero()) {
            return;
        }
        try {
            redis.opsForValue().set(key(productId), jsonMapper.writeValueAsString(stats), ttl);
        } catch (DataAccessException | JacksonException e) {
            log.warn("운영 현황 캐시에 쓰지 못했다: {}", e.toString());
        }
    }

    static String key(UUID productId) {
        return KEY_PREFIX + (productId == null ? ALL : productId);
    }
}
