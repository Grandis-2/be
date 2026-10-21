package com.grandis.nova.preorder.campaign.application;

import com.grandis.nova.preorder.cache.ClusterCacheEviction;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({CampaignProperties.class, CampaignProgressProperties.class, OpenAlertProperties.class,
        CampaignCacheProperties.class, CloseNoticeProperties.class, ScheduleNoticeProperties.class})
class CampaignConfig {

    /** 회차 원천과 그 위의 접수 현황을 함께 비운다. */
    @Bean
    CampaignCacheInvalidation campaignCacheInvalidation(CampaignCache cache, CampaignProgressService progress,
                                                        StringRedisTemplate redis, MeterRegistry meterRegistry) {
        return new CampaignCacheInvalidation(productId -> {
            cache.evict(productId);
            progress.evict(productId);
        }, redis, ClusterCacheEviction.retryExecutor("campaign-evict-retry-"), ClusterCacheEviction.RETRY_DELAYS,
                meterRegistry);
    }

    /** 회차 캐시 비우기 알림을 구독한다. */
    @Bean
    RedisMessageListenerContainer campaignEvictListener(RedisConnectionFactory connectionFactory,
                                                        CampaignCacheInvalidation invalidation) {
        return invalidation.listener(connectionFactory);
    }
}
