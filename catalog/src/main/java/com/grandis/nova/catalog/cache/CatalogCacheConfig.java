package com.grandis.nova.catalog.cache;

import com.grandis.nova.common.redis.cache.ReadCache;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

/** 읽기 캐시. 토큰 폐기 조회와 같은 Redis 연결(spring.data.redis)을 쓴다 — 키는 "catalog:cache:" 로 시작한다. */
@Configuration(proxyBeanMethods = false)
class CatalogCacheConfig {

    /** nova.read-cache.enabled=false 면 끈다(늘 DB) — 운영 기본은 켬. */
    @Bean
    ReadCache catalogReadCacheStore(StringRedisTemplate redis, JsonMapper json, ReadCacheRefresher refresher, ReadCacheMetrics metrics,
                                    @Value("${nova.read-cache.enabled:true}") boolean enabled) {
        return enabled ? new ReadCache(redis, json, "catalog", refresher, metrics) : ReadCache.off(json, "catalog");
    }
}
