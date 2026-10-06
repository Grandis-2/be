package com.grandis.nova.preorder.integration.catalog;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.web.service.registry.ImportHttpServices;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/** catalog 내부 API 클라이언트. 주소 · 타임아웃은 spring.http.serviceclient.catalog 설정으로 준다. */
@Configuration(proxyBeanMethods = false)
@ImportHttpServices(group = "catalog", types = CatalogClient.class)
class CatalogClientConfig {

    /** 다시 받기는 상품마다 한 번만 돌므로 상한은 넉넉하다. 넘치면 기다리지 않고 거절한다 — 다음 조회가 다시 시도한다. */
    static final int REFRESH_CONCURRENCY = 16;

    /** 캐시 다시 받기용 가상 스레드. 종료 때 남은 건은 버린다 — 가진 값을 쓰다가 다음 조회가 다시 받는다. */
    @Bean(name = CatalogReader.REFRESH_EXECUTOR)
    SimpleAsyncTaskExecutor catalogRefreshExecutor() {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("catalog-refresh-");
        executor.setVirtualThreads(true);
        executor.setConcurrencyLimit(REFRESH_CONCURRENCY);
        executor.setRejectTasksWhenLimitReached(true);
        executor.setCancelRemainingTasksOnClose(true);
        return executor;
    }

    /** 캐시 비우기 알림이 실패하면 이만큼씩 기다렸다 다시 알린다(짧은 Redis 순단을 넘긴다). */
    static final List<Duration> EVICT_RETRY_DELAYS = List.of(Duration.ofSeconds(1), Duration.ofSeconds(2),
            Duration.ofSeconds(4));

    /** Redis 장애 중 재시도가 쌓여 자원을 잡지 않게 한다. 넘치면 기다리지 않고 거절한다 — 실패로 세고 1분 갱신에 맡긴다. */
    static final int EVICT_RETRY_CONCURRENCY = 16;

    @Bean
    CatalogCacheInvalidation catalogCacheInvalidation(CatalogReader catalogReader, StringRedisTemplate redis,
                                                      MeterRegistry meterRegistry) {
        SimpleAsyncTaskExecutor retryExecutor = new SimpleAsyncTaskExecutor("catalog-evict-retry-");
        retryExecutor.setVirtualThreads(true);
        retryExecutor.setConcurrencyLimit(EVICT_RETRY_CONCURRENCY);
        retryExecutor.setRejectTasksWhenLimitReached(true);
        return new CatalogCacheInvalidation(catalogReader, redis, retryExecutor, EVICT_RETRY_DELAYS, meterRegistry);
    }

    /** 상품 캐시 비우기 알림을 구독한다. 모든 인스턴스가 같은 채널을 듣는다. */
    @Bean
    RedisMessageListenerContainer catalogEvictListener(RedisConnectionFactory connectionFactory,
                                                       CatalogCacheInvalidation invalidation) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener((message, pattern) ->
                        invalidation.onMessage(new String(message.getBody(), StandardCharsets.UTF_8)),
                new ChannelTopic(CatalogCacheInvalidation.CHANNEL));
        return container;
    }
}
