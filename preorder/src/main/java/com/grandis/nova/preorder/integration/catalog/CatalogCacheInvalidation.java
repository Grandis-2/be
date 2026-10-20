package com.grandis.nova.preorder.integration.catalog;

import com.grandis.nova.preorder.cache.ClusterCacheEviction;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;

/**
 * 상품 캐시 비우기를 모든 인스턴스에 알린다. 캐시는 인스턴스마다 따로인데 큐 메시지는 한 대만 받으므로,
 * 받은 인스턴스가 Redis 채널로 알리고 모든 인스턴스가 구독해 비운다. 끝내 알리지 못하면 나머지 인스턴스는 1분 갱신으로 따라온다.
 */
public class CatalogCacheInvalidation extends ClusterCacheEviction {

    static final String CHANNEL = "preorder:catalog-evict";
    static final String PUBLISH_FAILURE_METRIC = "preorder.catalog.evict.publish.failures";

    /** @param retryDelays 첫 실패 뒤 다시 알리기 전에 기다리는 시간들. 개수가 곧 재시도 횟수다 */
    CatalogCacheInvalidation(CatalogReader catalogReader, StringRedisTemplate redis, TaskExecutor retryExecutor,
                             List<Duration> retryDelays, MeterRegistry meterRegistry) {
        super(CHANNEL, "상품 캐시", PUBLISH_FAILURE_METRIC, catalogReader::evict, redis, retryExecutor, retryDelays,
                meterRegistry);
    }
}
