package com.grandis.nova.preorder.integration.catalog;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;

/**
 * 상품 캐시 비우기를 모든 인스턴스에 알린다. 캐시는 인스턴스마다 따로인데 큐 메시지는 한 대만 받으므로,
 * 받은 인스턴스가 Redis 채널로 알리고 모든 인스턴스가 구독해 비운다. 알리기에 실패하면 뒤에서 몇 번 다시 알리고,
 * 그래도 실패하면 지표 · 오류 로그를 남긴다(나머지 인스턴스는 1분 갱신으로 따라온다). 이벤트 처리는 막지 않는다.
 */
public class CatalogCacheInvalidation {

    static final String CHANNEL = "preorder:catalog-evict";
    static final String PUBLISH_FAILURE_METRIC = "preorder.catalog.evict.publish.failures";

    private static final Logger log = LoggerFactory.getLogger(CatalogCacheInvalidation.class);

    private final CatalogReader catalogReader;
    private final StringRedisTemplate redis;
    private final TaskExecutor retryExecutor;
    private final List<Duration> retryDelays;
    private final Counter publishFailures;

    /** @param retryDelays 첫 실패 뒤 다시 알리기 전에 기다리는 시간들. 개수가 곧 재시도 횟수다 */
    CatalogCacheInvalidation(CatalogReader catalogReader, StringRedisTemplate redis, TaskExecutor retryExecutor,
                             List<Duration> retryDelays, MeterRegistry meterRegistry) {
        this.catalogReader = catalogReader;
        this.redis = redis;
        this.retryExecutor = retryExecutor;
        this.retryDelays = List.copyOf(retryDelays);
        this.publishFailures = meterRegistry.counter(PUBLISH_FAILURE_METRIC);
    }

    /** 이 인스턴스는 바로 비우고 나머지에 알린다. 알리기 실패는 뒤에서 다시 하고, 호출한 쪽에는 올리지 않는다. */
    public void evictEverywhere(Long productId) {
        catalogReader.evict(productId);
        if (publish(productId)) {
            return;
        }
        try {
            retryExecutor.execute(() -> retryPublish(productId));
        } catch (TaskRejectedException e) {
            giveUp(productId);
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

    private void retryPublish(Long productId) {
        for (Duration delay : retryDelays) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                giveUp(productId);
                return;
            }
            if (publish(productId)) {
                return;
            }
        }
        giveUp(productId);
    }

    private boolean publish(Long productId) {
        try {
            redis.convertAndSend(CHANNEL, productId.toString());
            return true;
        } catch (RuntimeException e) {
            log.warn("상품 캐시 비우기를 다른 인스턴스에 알리지 못했다 productId={}: {}", productId, e.toString());
            return false;
        }
    }

    private void giveUp(Long productId) {
        publishFailures.increment();
        log.error("상품 캐시 비우기를 끝내 알리지 못했다 — 다른 인스턴스는 1분 갱신으로 따라온다 productId={}", productId);
    }
}
