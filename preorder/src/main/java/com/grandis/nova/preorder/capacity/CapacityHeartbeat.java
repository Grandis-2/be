package com.grandis.nova.preorder.capacity;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 이 태스크가 살아 있고 초당 얼마를 받는지 공유 Redis 의 태스크 표({@link #KEY})에 주기마다 적는다. 표 모양은
 * contracts/preorder-capacity-heartbeat.md 다. 적을 때 죽은 태스크의 행을 지우고, 내려갈 때 제 행을 지운다(줄어든 몫이 바로 반영되게).
 * 실패해도 접수에는 영향이 없다 — 대기열은 행이 사라지면 그 몫을 뺀다.
 */
class CapacityHeartbeat {

    static final String KEY = "preorder:capacity:tasks";
    static final String FAILURE_METRIC = "preorder.capacity.heartbeat.failures";
    static final String ALIVE_METRIC = "preorder.capacity.tasks.alive";

    private static final Logger log = LoggerFactory.getLogger(CapacityHeartbeat.class);
    private static final RedisScript<Long> SCRIPT =
            RedisScript.of(new ClassPathResource("scripts/capacity_heartbeat.lua"), Long.class);

    private final StringRedisTemplate redis;
    private final CapacityHeartbeatProperties properties;
    private final String taskId;
    private final Counter failures;
    private final AtomicLong alive = new AtomicLong();

    CapacityHeartbeat(StringRedisTemplate redis, CapacityHeartbeatProperties properties, MeterRegistry meterRegistry) {
        this(redis, properties, UUID.randomUUID().toString(), meterRegistry);
    }

    CapacityHeartbeat(StringRedisTemplate redis, CapacityHeartbeatProperties properties, String taskId,
                      MeterRegistry meterRegistry) {
        this.redis = redis;
        this.properties = properties;
        this.taskId = taskId;
        this.failures = meterRegistry.counter(FAILURE_METRIC);
        meterRegistry.gauge(ALIVE_METRIC, alive);
    }

    @Scheduled(fixedDelayString = "${nova.capacity.heartbeat.interval:5s}", initialDelay = 0)
    void beat() {
        try {
            Long count = redis.execute(SCRIPT, List.of(KEY), taskId,
                    Integer.toString(properties.acceptPerSecond()), Long.toString(properties.staleAfter().toSeconds()));
            alive.set(count == null ? 0 : count);
        } catch (RuntimeException e) {
            failures.increment();
            log.warn("태스크 하트비트를 적지 못했다 — 길어지면 대기열이 이 태스크의 몫을 뺀다 taskId={}", taskId, e);
        }
    }

    @PreDestroy
    void leave() {
        try {
            redis.opsForHash().delete(KEY, taskId);
        } catch (RuntimeException e) {
            log.warn("내려가며 태스크 행을 지우지 못했다 — 판정 시간 뒤 다른 태스크가 지운다 taskId={}", taskId, e);
        }
    }

    String taskId() {
        return taskId;
    }
}
