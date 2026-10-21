package com.grandis.nova.preorder.cache;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 인스턴스마다 따로인 캐시의 상품 하나를 모든 인스턴스에서 비운다. 이 인스턴스는 바로 비우고 Redis 채널로 알려 모두가 구독해 비운다.
 * 알리기에 실패하면 뒤에서 몇 번 다시 알리고, 그래도 실패하면 지표 · 오류 로그를 남긴다(나머지 인스턴스는 캐시 수명으로 따라온다).
 * 부른 쪽은 막지 않는다.
 */
public class ClusterCacheEviction {

    /** 알리기가 실패하면 이만큼씩 기다렸다 다시 알린다(짧은 Redis 순단을 넘긴다). */
    public static final List<Duration> RETRY_DELAYS = List.of(Duration.ofSeconds(1), Duration.ofSeconds(2),
            Duration.ofSeconds(4));
    /** Redis 장애 중 재시도가 쌓여 자원을 잡지 않게 한다. 넘치면 기다리지 않고 거절한다 — 실패로 세고 수명에 맡긴다. */
    static final int RETRY_CONCURRENCY = 16;

    private static final Logger log = LoggerFactory.getLogger(ClusterCacheEviction.class);

    private final String channel;
    private final String name;
    private final Consumer<UUID> evictLocal;
    private final StringRedisTemplate redis;
    private final TaskExecutor retryExecutor;
    private final List<Duration> retryDelays;
    private final Counter publishFailures;

    /**
     * @param name        로그에 쓰는 캐시 이름
     * @param retryDelays 첫 실패 뒤 다시 알리기 전에 기다리는 시간들. 개수가 곧 재시도 횟수다
     */
    public ClusterCacheEviction(String channel, String name, String failureMetric, Consumer<UUID> evictLocal,
                                StringRedisTemplate redis, TaskExecutor retryExecutor, List<Duration> retryDelays,
                                MeterRegistry meterRegistry) {
        this.channel = channel;
        this.name = name;
        this.evictLocal = evictLocal;
        this.redis = redis;
        this.retryExecutor = retryExecutor;
        this.retryDelays = List.copyOf(retryDelays);
        this.publishFailures = meterRegistry.counter(failureMetric);
    }

    /** 재시도용 가상 스레드 실행기. */
    public static SimpleAsyncTaskExecutor retryExecutor(String threadNamePrefix) {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor(threadNamePrefix);
        executor.setVirtualThreads(true);
        executor.setConcurrencyLimit(RETRY_CONCURRENCY);
        executor.setRejectTasksWhenLimitReached(true);
        return executor;
    }

    /** 이 채널을 구독하는 컨테이너. 모든 인스턴스가 같은 채널을 듣는다. */
    public RedisMessageListenerContainer listener(RedisConnectionFactory connectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener((message, pattern) ->
                onMessage(new String(message.getBody(), StandardCharsets.UTF_8)), new ChannelTopic(channel));
        return container;
    }

    /** 이 인스턴스는 바로 비우고 나머지에 알린다. 알리기 실패는 뒤에서 다시 하고, 호출한 쪽에는 올리지 않는다. */
    public void evictEverywhere(UUID productId) {
        evictLocal.accept(productId);
        if (publish(productId)) {
            return;
        }
        try {
            retryExecutor.execute(() -> retryPublish(productId));
        } catch (TaskRejectedException e) {
            giveUp(productId);
        }
    }

    /** 다른 인스턴스(또는 자신)가 보낸 알림. 읽을 수 없는 본문은 버린다 — 캐시 수명이 바로잡는다. */
    public void onMessage(String body) {
        try {
            evictLocal.accept(UUID.fromString(body));
        } catch (IllegalArgumentException e) {
            log.warn("읽을 수 없는 {} 비우기 알림을 버린다: {}", name, body);
        }
    }

    private void retryPublish(UUID productId) {
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

    private boolean publish(UUID productId) {
        try {
            redis.convertAndSend(channel, productId.toString());
            return true;
        } catch (RuntimeException e) {
            log.warn("{} 비우기를 다른 인스턴스에 알리지 못했다 productId={}: {}", name, productId, e.toString());
            return false;
        }
    }

    private void giveUp(UUID productId) {
        publishFailures.increment();
        log.error("{} 비우기를 끝내 알리지 못했다 — 다른 인스턴스는 캐시 수명으로 따라온다 productId={}", name, productId);
    }
}
