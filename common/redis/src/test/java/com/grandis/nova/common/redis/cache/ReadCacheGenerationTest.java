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

    @Test
    @DisplayName("스냅샷 복원으로 세대가 되돌아가면 떠 있는 인스턴스는 세대를 올려서 읽는다 — 돌아온 옛 값을 내지 않고 옛 몫을 정리해 새 값을 넣는다")
    void restoredSnapshotIsDetectedByLiveInstance() {
        redis.opsForValue().set(GENERATION, "0");
        ReadCache cache = new ReadCache(redis, JSON, "svc");
        ReadCache.Spec<Item> spec = cache.spec("item", Item.class, Duration.ofMinutes(10), 2_000);
        AtomicInteger loads = new AtomicInteger();
        cache.bumpNow("products");
        cache.bumpNow("products");
        cache.get(spec, "products", "1", () -> load("snapshot-time", loads));   // 세대 2
        Map<String, byte[]> snapshot = dump();
        for (int i = 0; i < 3; i++) {
            cache.bumpNow("products");
            cache.get(spec, "products", "1", () -> load("before-restore", loads));
        }
        long highest = generation();   // 5

        restore(snapshot);   // 세대 2, 값 snapshot-time

        assertThat(cache.get(spec, "products", "1", () -> load("fresh", loads)).name()).as("돌아온 옛 값을 내지 않는다").isEqualTo("fresh");
        assertThat(generation()).as("본 번호보다 크게 올렸다").isGreaterThan(highest);
        assertThat(redis.keys("svc:cache:item:*")).as("새 세대 값과, 스냅샷에서 돌아온 옛 세대 값(아무도 읽지 않고 TTL 로 사라진다)만 남는다")
                .hasSize(2).anyMatch(key -> key.contains(":products." + generation() + ":"));
        for (int i = 0; i < 5; i++) {
            String id = "n" + i;
            cache.get(spec, "products", id, () -> load("n", loads));
        }
        int before = loads.get();
        for (int i = 0; i < 5; i++) {
            String id = "n" + i;
            cache.get(spec, "products", id, () -> load("n", loads));
        }
        assertThat(loads.get() - before).as("옛 몫이 풀려 새 키를 모두 넣었다").isZero();
    }

    @Test
    @DisplayName("여러 인스턴스가 함께 알아채도 세대는 줄지 않고 각자 본 번호보다 커진다 — 모두 새 값을 낸다")
    void concurrentDetectionNeverLowersGeneration() throws Exception {
        redis.opsForValue().set(GENERATION, "0");
        List<ReadCache> caches = List.of(new ReadCache(redis, JSON, "svc"), new ReadCache(redis, JSON, "svc"), new ReadCache(redis, JSON, "svc"));
        List<ReadCache.Spec<Item>> specs = caches.stream().map(c -> c.spec("item", Item.class, Duration.ofMinutes(10))).toList();
        AtomicInteger loads = new AtomicInteger();
        caches.get(0).get(specs.get(0), "products", "1", () -> load("snapshot-time", loads));
        Map<String, byte[]> snapshot = dump();
        caches.get(0).bumpNow("products");
        for (int i = 0; i < caches.size(); i++) {
            caches.get(i).get(specs.get(i), "products", "1", () -> load("before-restore", loads));   // 모두 세대 1 을 봤다
        }

        restore(snapshot);   // 세대 0

        ExecutorService pool = Executors.newFixedThreadPool(caches.size());
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Item>> served = new java.util.ArrayList<>();
            for (int i = 0; i < caches.size(); i++) {
                int at = i;
                served.add(pool.submit(() -> {
                    go.await();
                    return caches.get(at).get(specs.get(at), "products", "1", () -> load("fresh", loads));
                }));
            }
            go.countDown();
            for (Future<Item> item : served) {
                assertThat(item.get().name()).isEqualTo("fresh");
            }
        } finally {
            pool.shutdownNow();
        }
        long raised = generation();
        assertThat(raised).isGreaterThan(1);
        for (int i = 0; i < caches.size(); i++) {
            assertThat(caches.get(i).get(specs.get(i), "products", "1", () -> load("unexpected", loads)).name()).isEqualTo("fresh");
        }
        assertThat(generation()).as("알아챈 뒤에는 더 올리지 않는다").isEqualTo(raised);
    }

    @Test
    @DisplayName("복원 뒤 새로 뜬 인스턴스는 알아채지 못한다 — 문서의 운영 절차(EVAL)를 돌리면 새 값을 내고, 이미 큰 세대는 줄이지 않는다")
    void freshInstanceNeedsTheDocumentedProcedure() throws Exception {
        redis.opsForValue().set(GENERATION, "0");
        ReadCache before = new ReadCache(redis, JSON, "svc");
        ReadCache.Spec<Item> beforeSpec = before.spec("item", Item.class, Duration.ofMinutes(10));
        AtomicInteger loads = new AtomicInteger();
        before.get(beforeSpec, "products", "1", () -> load("snapshot-time", loads));
        Map<String, byte[]> snapshot = dump();
        before.bumpNow("products");
        restore(snapshot);

        ReadCache restarted = new ReadCache(redis, JSON, "svc");   // 본 번호가 없다
        ReadCache.Spec<Item> spec = restarted.spec("item", Item.class, Duration.ofMinutes(10));
        assertThat(restarted.get(spec, "products", "1", () -> load("fresh", loads)).name()).as("절차 전에는 돌아온 옛 값을 낸다")
                .isEqualTo("snapshot-time");

        long now = System.currentTimeMillis();
        procedure(GENERATION, now);
        assertThat(generation()).isEqualTo(now);
        assertThat(restarted.get(spec, "products", "1", () -> load("fresh", loads)).name()).isEqualTo("fresh");

        procedure(GENERATION, now - 60_000);
        assertThat(generation()).as("이미 큰 세대는 줄이지 않는다").isEqualTo(now);
    }

    /** ReadCache 문서에 적은 복원 뒤 운영 절차 그대로 — 운영자가 valkey-cli 로 돌린다. */
    private static void procedure(String key, long epochMillis) throws Exception {
        var result = REDIS.execInContainer("valkey-cli", "EVAL",
                "local v=tonumber(redis.call('GET',KEYS[1]) or '0') if v<tonumber(ARGV[1]) then redis.call('SET',KEYS[1],ARGV[1]) end",
                "1", key, String.valueOf(epochMillis));
        assertThat(result.getExitCode()).as(result.getStderr()).isZero();
        assertThat(result.getStdout()).doesNotContain("ERR");
    }

    private static long generation() {
        return Long.parseLong(redis.opsForValue().get(GENERATION));
    }

    private static Item load(String name, AtomicInteger loads) {
        loads.incrementAndGet();
        return new Item(name);
    }

    /** Valkey 의 키를 모두 DUMP 해 둔다 — 스냅샷. */
    private static Map<String, byte[]> dump() {
        Map<String, byte[]> dump = new LinkedHashMap<>();
        try (RedisConnection connection = redis.getConnectionFactory().getConnection()) {
            for (String key : redis.keys("*")) {
                dump.put(key, connection.keyCommands().dump(key.getBytes(StandardCharsets.UTF_8)));
            }
        }
        return dump;
    }

    /** 스냅샷으로 되돌린다 — 그 뒤에 생긴 키는 없어지고, 세대와 값은 그때로 돌아간다(TTL 은 영구로 넣는다 — 시험 안에서는 만료되지 않을 값이다). */
    private static void restore(Map<String, byte[]> dump) {
        try (RedisConnection connection = redis.getConnectionFactory().getConnection()) {
            connection.serverCommands().flushAll();
            dump.forEach((key, value) -> connection.keyCommands().restore(key.getBytes(StandardCharsets.UTF_8), 0, value, false));
        }
    }
}
