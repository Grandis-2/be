package com.grandis.nova.preorder.campaign.application;

import com.grandis.nova.preorder.cache.ClusterCacheEviction;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 회차 캐시({@link CampaignCache} · 접수 현황) 비우기를 모든 인스턴스에 알린다. 회차를 바꾼 트랜잭션의 커밋 뒤에 비운다 —
 * 커밋 전에 비우면 그사이 다른 요청이 옛 값을 다시 채운다. 롤백되면 비우지 않는다.
 */
public class CampaignCacheInvalidation extends ClusterCacheEviction {

    static final String CHANNEL = "preorder:campaign-evict";
    static final String PUBLISH_FAILURE_METRIC = "preorder.campaign.evict.publish.failures";

    CampaignCacheInvalidation(Consumer<UUID> evictLocal, StringRedisTemplate redis, TaskExecutor retryExecutor,
                              List<Duration> retryDelays, MeterRegistry meterRegistry) {
        super(CHANNEL, "회차 캐시", PUBLISH_FAILURE_METRIC, evictLocal, redis, retryExecutor, retryDelays, meterRegistry);
    }

    /** 트랜잭션 안이면 커밋 뒤에, 밖이면 바로 비운다. 같은 트랜잭션에서 여러 번 불러도 비우기는 그만큼 한다(가볍다). */
    public void evictAfterCommit(UUID productId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            evictEverywhere(productId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                evictEverywhere(productId);
            }
        });
    }
}
