package com.grandis.nova.preorder.capacity;

import com.grandis.nova.preorder.support.PreorderIntegrationTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 대기열이 읽는 태스크 표의 모양(contracts/preorder-capacity-heartbeat.md). */
@PreorderIntegrationTest
class CapacityHeartbeatTest {

    static final CapacityHeartbeatProperties PROPERTIES =
            new CapacityHeartbeatProperties(true, Duration.ofSeconds(5), Duration.ofSeconds(15), 120);

    @Autowired
    StringRedisTemplate redis;

    @Test
    void 마지막_시각_처음_시각_용량_판정을_적고_다시_적어도_처음_시각은_그대로다() {
        CapacityHeartbeat heartbeat = heartbeat();

        heartbeat.beat();
        String[] first = row(heartbeat.taskId());
        redis.opsForHash().put(CapacityHeartbeat.KEY, heartbeat.taskId(),
                first[0] + "|" + (Long.parseLong(first[1]) - 60) + "|120|15");
        heartbeat.beat();
        String[] second = row(heartbeat.taskId());

        assertThat(first).hasSize(4);
        assertThat(first[0]).as("처음 알린 때가 처음 시각").isEqualTo(first[1]);
        assertThat(first[2]).isEqualTo("120");
        assertThat(first[3]).isEqualTo("15");
        assertThat(Long.parseLong(second[1])).isEqualTo(Long.parseLong(first[1]) - 60);
    }

    @Test
    void 판정_시간을_넘긴_태스크의_행은_적을_때_지운다() {
        String dead = "dead-" + UUID.randomUUID();
        Long millis = redis.execute((RedisCallback<Long>) connection -> connection.serverCommands().time());
        long now = millis / 1000;
        redis.opsForHash().put(CapacityHeartbeat.KEY, dead, (now - 16) + "|" + (now - 600) + "|100|15");
        CapacityHeartbeat heartbeat = heartbeat();

        heartbeat.beat();

        assertThat(redis.opsForHash().hasKey(CapacityHeartbeat.KEY, dead)).isFalse();
        assertThat(redis.opsForHash().hasKey(CapacityHeartbeat.KEY, heartbeat.taskId())).isTrue();
    }

    @Test
    void 내려가면_제_행을_지운다() {
        CapacityHeartbeat heartbeat = heartbeat();
        heartbeat.beat();

        heartbeat.leave();

        assertThat(redis.opsForHash().hasKey(CapacityHeartbeat.KEY, heartbeat.taskId())).isFalse();
    }

    /** 내려간 뒤에는 적지 않는다 — 진행 중이던 적기가 지운 행을 되살리지 않게. */
    @Test
    void 내려간_뒤에는_다시_적지_않는다() {
        CapacityHeartbeat heartbeat = heartbeat();
        heartbeat.beat();
        heartbeat.leave();

        heartbeat.beat();

        assertThat(redis.opsForHash().hasKey(CapacityHeartbeat.KEY, heartbeat.taskId())).isFalse();
    }

    @Test
    void 판정_시간_넘게_적지_못하면_생존_지표는_0() {
        AtomicLong nanos = new AtomicLong();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CapacityHeartbeat heartbeat = new CapacityHeartbeat(redis, PROPERTIES, "task-" + UUID.randomUUID(), registry,
                nanos::get);
        heartbeat.beat();
        double alive = registry.get(CapacityHeartbeat.ALIVE_METRIC).gauge().value();

        nanos.addAndGet(Duration.ofSeconds(16).toNanos());

        assertThat(alive).isGreaterThanOrEqualTo(1);
        assertThat(registry.get(CapacityHeartbeat.ALIVE_METRIC).gauge().value()).isZero();
    }

    /** 판정은 Redis 에 초로 적으므로 소수 초를 받으면 내림돼 살아 있는 태스크가 일찍 지워진다. */
    @Test
    void 초_단위가_아닌_주기_판정은_기동에서_막는다() {
        assertThatThrownBy(() -> new CapacityHeartbeatProperties(true, Duration.ofMillis(1900), Duration.ofSeconds(4),
                100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CapacityHeartbeatProperties(true, Duration.ofSeconds(2), Duration.ofMillis(4500),
                100)).isInstanceOf(IllegalArgumentException.class);
    }

    private CapacityHeartbeat heartbeat() {
        return new CapacityHeartbeat(redis, PROPERTIES, "task-" + UUID.randomUUID(), new SimpleMeterRegistry(),
                System::nanoTime);
    }

    private String[] row(String taskId) {
        Object value = redis.opsForHash().get(CapacityHeartbeat.KEY, taskId);
        assertThat(value).isNotNull();
        return value.toString().split("\\|");
    }
}
