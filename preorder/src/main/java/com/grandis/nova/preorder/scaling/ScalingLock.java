package com.grandis.nova.preorder.scaling;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 태스크 사이 잠금(공유 Redis). 맞추기를 한 번에 하나씩 돌려, 먼저 읽은 태스크의 늦은 호출이 나중에 읽은 값을 덮지 않게 한다 —
 * 잠금을 잡은 순서가 읽은 순서이므로 마지막에 넣는 값이 가장 새 값이다.
 */
class ScalingLock {

    static final String KEY = "preorder:scaling:lock";
    /** 제 것일 때만 지운다 — 수명이 지나 다른 태스크가 잡은 잠금을 풀지 않게. */
    private static final RedisScript<Long> RELEASE = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redis;
    private final Duration ttl;

    ScalingLock(StringRedisTemplate redis, Duration ttl) {
        this.redis = redis;
        this.ttl = ttl;
    }

    /** 잡으면 풀 때 쓸 표식, 다른 태스크가 쥐고 있으면 null. */
    String tryAcquire() {
        String token = UUID.randomUUID().toString();
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(KEY, token, ttl)) ? token : null;
    }

    void release(String token) {
        redis.execute(RELEASE, List.of(KEY), token);
    }
}
