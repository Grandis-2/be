package com.grandis.nova.common.redis.cache;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 세대 키가 사라지거나 되돌아갈 때(스냅샷 복원 · 사람이 지움) — 옛 세대의 값을 다시 내지 않고, 옛 몫을 정리하는지.
 */
@DisplayName("Redis 읽기 캐시 — 세대 키가 사라지거나 되돌아갈 때")
class ReadCacheGenerationTest {

    private static final GenericContainer<?> REDIS = new GenericContainer<>("valkey/valkey:9").withExposedPorts(6379);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String GENERATION = "svc:cache:gen:products";

    static LettuceConnectionFactory connections;
    static StringRedisTemplate redis;

    record Item(String name) {
    }

    @BeforeAll
    static void start() {
        REDIS.start();
        connections = new LettuceConnectionFactory(new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)),
                LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(300)).build());
        connections.afterPropertiesSet();
        connections.start();
        redis = new StringRedisTemplate(connections);
    }

    @AfterAll
    static void stop() {
        connections.destroy();
        REDIS.stop();
    }

    @BeforeEach
    void flush() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @Test
    @DisplayName("없는 세대는 0 이 아니라 지금 시각(epoch ms)에서 시작한다 — 처음 읽어도, 처음 올려도")
    void missingGenerationStartsAtNow() {
        ReadCache cache = new ReadCache(redis, JSON, "svc");
        ReadCache.Spec<Item> spec = cache.spec("item", Item.class, Duration.ofMinutes(10));
        long before = System.currentTimeMillis();

        cache.get(spec, "products", "1", () -> new Item("v"));
        assertThat(generation()).isBetween(before, System.currentTimeMillis());

        cache.bumpNow("reviews");
        assertThat(Long.parseLong(redis.opsForValue().get("svc:cache:gen:reviews"))).isGreaterThan(before);
    }

    @Test
    @DisplayName("세대 키를 지워도 옛 세대의 값을 다시 내지 않는다 — 새로 채우고, 옛 세대로 넣은 몫을 돌려준다")
    void deletedGenerationDoesNotServeOldValues() {
        redis.opsForValue().set(GENERATION, "0");
        ReadCache cache = new ReadCache(redis, JSON, "svc");
        ReadCache.Spec<Item> spec = cache.spec("item", Item.class, Duration.ofMinutes(10));
        AtomicInteger loads = new AtomicInteger();
        cache.get(spec, "products", "1", () -> load("old", loads));
        long used = spec.budget().used();

        redis.delete(GENERATION);   // 사람이 지움 · 새 캐시

        assertThat(cache.get(spec, "products", "1", () -> load("new", loads)).name()).isEqualTo("new");
        assertThat(redis.keys("svc:cache:item:*")).as("옛 세대 값은 지우고 새 세대 값만 남는다").hasSize(1);
        assertThat(spec.budget().used()).as("옛 몫을 돌려줬다 — 새 값 하나 몫만 남는다(세대 번호가 길어진 만큼 키가 크다)").isLessThan(2 * used);
    }

    private static long generation() {
        return Long.parseLong(redis.opsForValue().get(GENERATION));
    }

    private static Item load(String name, AtomicInteger loads) {
        loads.incrementAndGet();
        return new Item(name);
    }

}
