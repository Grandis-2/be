package com.grandis.nova.preorder.capacity;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
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
import java.util.function.LongSupplier;

/**
 * 이 태스크가 살아 있고 초당 얼마를 받는지 공유 Redis 의 태스크 표({@link #KEY})에 주기마다 적는다. 표 모양은
 * contracts/preorder-capacity-heartbeat.md 다. 적을 때 죽은 태스크의 행을 지우고, 내려갈 때 제 행을 지운다(줄어든 몫이 바로 반영되게).
 * 실패해도 접수에는 영향이 없다 — 대기열은 행이 사라지면 그 몫을 뺀다.
 * 적기와 지우기는 잠금으로 한 번에 하나다 — 내려가며 지운 뒤 진행 중이던 적기가 행을 되살리지 않게.
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
    private final LongSupplier nanoTime;
    private final Object lock = new Object();
    /** 마지막으로 적은 때 Redis 가 센 살아 있는 태스크 수와 그 시각(nanoTime). 지표 수집은 잠금 없이 읽는다. */
    private volatile long alive;
    private volatile long aliveAt;
    private boolean left;

    CapacityHeartbeat(StringRedisTemplate redis, CapacityHeartbeatProperties properties, MeterRegistry meterRegistry) {
        this(redis, properties, UUID.randomUUID().toString(), meterRegistry, System::nanoTime);
    }

    CapacityHeartbeat(StringRedisTemplate redis, CapacityHeartbeatProperties properties, String taskId,
                      MeterRegistry meterRegistry, LongSupplier nanoTime) {
        this.redis = redis;
        this.properties = properties;
        this.taskId = taskId;
        this.failures = meterRegistry.counter(FAILURE_METRIC);
        this.nanoTime = nanoTime;
        this.aliveAt = nanoTime.getAsLong();
        Gauge.builder(ALIVE_METRIC, this, CapacityHeartbeat::alive).register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${nova.capacity.heartbeat.interval:5s}", initialDelay = 0)
    void beat() {
        synchronized (lock) {
            if (left) {
                return;
            }
            try {
                Long count = redis.execute(SCRIPT, List.of(KEY), taskId, Integer.toString(properties.acceptPerSecond()),
                        Long.toString(properties.staleAfter().toSeconds()));
                alive = count == null ? 0 : count;
                aliveAt = nanoTime.getAsLong();
            } catch (RuntimeException e) {
                failures.increment();
                log.warn("태스크 하트비트를 적지 못했다 — 길어지면 대기열이 이 태스크의 몫을 뺀다 taskId={}", taskId, e);
            }
        }
    }

    @PreDestroy
    void leave() {
        synchronized (lock) {
            left = true;
            try {
                redis.opsForHash().delete(KEY, taskId);
            } catch (RuntimeException e) {
                log.warn("내려가며 태스크 행을 지우지 못했다 — 판정 시간 뒤 다른 태스크가 지운다 taskId={}", taskId, e);
            }
        }
    }

    /** 판정 시간 넘게 적지 못했으면 0 — 이 태스크의 행도 지워졌을 수 있어 마지막 값을 지금 값으로 내지 않는다. */
    double alive() {
        return nanoTime.getAsLong() - aliveAt > properties.staleAfter().toNanos() ? 0 : alive;
    }

    String taskId() {
        return taskId;
    }
}
