package com.grandis.nova.waitingroom.redis;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * resources/redis 의 Lua 스크립트와 실행. 스크립트는 EVALSHA 로 돌고, 캐시에 없으면 본문으로 다시 보낸다.
 * 응답 칸은 정수(Long)나 문자열로 오므로 {@link #text}/{@link #number} 로 읽는다.
 */
@Component
public class LuaScripts {

    final RedisScript<List> enqueue = load("enqueue");
    final RedisScript<List> status = load("queue_status");
    final RedisScript<List> depth = load("queue_depth");
    final RedisScript<List> apply = load("allocation_apply");
    final RedisScript<List> publishSnapshot = load("snapshot_publish");
    final RedisScript<List> readSnapshot = load("snapshot_read");
    final RedisScript<List> readProducts = load("products_read");
    final RedisScript<List> acquireLeader = load("leader_acquire");
    final RedisScript<Long> releaseLeader = RedisScript.of(new ClassPathResource("redis/leader_release.lua"), Long.class);
    final RedisScript<List> heartbeat = load("gateway_heartbeat");
    final RedisScript<Long> leave = RedisScript.of(new ClassPathResource("redis/gateway_leave.lua"), Long.class);
    final RedisScript<List> sweep = load("sweep");
    final RedisScript<Long> closeQueue = RedisScript.of(new ClassPathResource("redis/close_queue.lua"), Long.class);

    private final ReactiveStringRedisTemplate redis;

    public LuaScripts(ReactiveStringRedisTemplate redis) {
        this.redis = redis;
    }

    @SuppressWarnings("unchecked")
    Mono<List<Object>> list(RedisScript<List> script, List<String> keys, List<String> args) {
        return redis.execute(script, keys, args).next().map(reply -> (List<Object>) reply);
    }

    Mono<Long> single(RedisScript<Long> script, List<String> keys, List<String> args) {
        return redis.execute(script, keys, args).next();
    }

    static String text(List<Object> reply, int index) {
        return String.valueOf(reply.get(index));
    }

    static long number(List<Object> reply, int index) {
        Object value = reply.get(index);
        return value instanceof Long number ? number : Long.parseLong(String.valueOf(value));
    }

    private static RedisScript<List> load(String name) {
        return RedisScript.of(new ClassPathResource("redis/" + name + ".lua"), List.class);
    }
}
