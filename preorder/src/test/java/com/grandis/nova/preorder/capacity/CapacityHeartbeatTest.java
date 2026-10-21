package com.grandis.nova.preorder.capacity;

import com.grandis.nova.preorder.support.PreorderIntegrationTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

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

    private CapacityHeartbeat heartbeat() {
        return new CapacityHeartbeat(redis, PROPERTIES, "task-" + UUID.randomUUID(), new SimpleMeterRegistry());
    }

    private String[] row(String taskId) {
        Object value = redis.opsForHash().get(CapacityHeartbeat.KEY, taskId);
        assertThat(value).isNotNull();
        return value.toString().split("\\|");
    }
}
