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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 세대 키가 사라지거나 되돌아갈 때(스냅샷 복원 · 사람이 지움) — 옛 세대의 값을 다시 내지 않고, 옛 몫을 정리하는지.
 */
@DisplayName("Redis 읽기 캐시 — 세대 키가 사라지거나 되돌아갈 때")
class ReadCacheGenerationTest {

    private static final GenericContainer<?> REDIS = TestRedis.container();
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String GENERATION = "svc:cache:gen:products";

    static LettuceConnectionFactory connections;
    static LettuceConnectionFactory fixtureConnections;
    static StringRedisTemplate redis;
    /** {@code @BeforeEach} 의 비우기용. 제한 시간이 넉넉하다 — {@link TestRedis} */
    static StringRedisTemplate fixture;

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
        fixtureConnections = TestRedis.fixtureConnections(REDIS);
        fixture = new StringRedisTemplate(fixtureConnections);
    }

    @AfterAll
    static void stop() {
        fixtureConnections.destroy();
        connections.destroy();
        REDIS.stop();
    }

    @BeforeEach
    void flush() {
        fixture.getConnectionFactory().getConnection().serverCommands().flushAll();
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
    @DisplayName("한 세대를 나눠 쓰는 캐시 중 늦게 읽은 캐시도 복원을 알아챈다 — 다른 캐시가 본 더 큰 번호로 판정해 스냅샷의 옛 값을 내지 않는다")
    void sharedGenerationDetectsRestoreForLaggingCache() {
        redis.opsForValue().set(GENERATION, "0");
        ReadCache cache = new ReadCache(redis, JSON, "svc");
        ReadCache.Spec<Item> detail = cache.spec("detail", Item.class, Duration.ofMinutes(10));
        ReadCache.Spec<Item> list = cache.spec("list", Item.class, Duration.ofMinutes(10));
        ReadCache other = new ReadCache(redis, JSON, "svc");   // 다른 인스턴스
        ReadCache.Spec<Item> otherList = other.spec("list", Item.class, Duration.ofMinutes(10));
        AtomicInteger loads = new AtomicInteger();

        for (int i = 0; i < 5; i++) {
            cache.bumpNow("products");
        }
        cache.get(list, "products", "1", () -> load("list-at-5", loads));   // 목록은 5 까지만 봤다
        cache.bumpNow("products");
        cache.bumpNow("products");
        other.get(otherList, "products", "1", () -> load("list-at-7", loads));   // 다른 인스턴스가 세대 7 의 목록을 넣었다
        Map<String, byte[]> snapshot = dump();   // 세대 7
        cache.bumpNow("products");
        cache.bumpNow("products");
        cache.get(detail, "products", "1", () -> load("detail-at-9", loads));   // 상세는 9 를 봤다

        restore(snapshot);

        assertThat(cache.get(list, "products", "1", () -> load("fresh", loads)).name()).as("목록도 세대 7 의 옛 값을 내지 않는다").isEqualTo("fresh");
        assertThat(generation()).isGreaterThan(9);
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

    @Test
    @DisplayName("세대를 읽은 사이 다른 스레드가 올린 세대를 먼저 봐도 되돌아간 것으로 보지 않는다 — 정상 올리기 한 번에 세대는 한 번만 오른다")
    void concurrentBumpIsNotMistakenForRollback() throws Exception {
        redis.opsForValue().set(GENERATION, "0");
        PausingRedisTemplate template = new PausingRedisTemplate(connections, GENERATION::equals);
        ReadCache cache = new ReadCache(template, JSON, "svc");
        ReadCache.Spec<Item> spec = cache.spec("item", Item.class, Duration.ofMinutes(10));
        AtomicInteger loads = new AtomicInteger();
        cache.get(spec, "products", "1", () -> load("v0", loads));   // 본 번호 0

        AtomicReference<Object> served = new AtomicReference<>();
        Thread reader = paused(template, served, () -> cache.get(spec, "products", "1", () -> load("late", loads)));   // 세대 0 을 읽고 멈춘다
        cache.bumpNow("products");   // 세대 1
        cache.get(spec, "products", "1", () -> load("v1", loads));   // 다른 스레드가 1 을 본다
        resume(template, reader);

        assertThat(served.get()).as("멈췄던 조회도 끝까지 답했다").isInstanceOf(Item.class);
        assertThat(generation()).as("되돌아간 것으로 보고 더 올리지 않았다").isEqualTo(1);
    }

    @Test
    @DisplayName("본 번호가 작은 인스턴스가 늦게 올려도 세대는 줄지 않는다 — 큰 쪽이 먼저 올린 번호가 남는다")
    void laterSmallerRaiseKeepsLargerGeneration() throws Exception {
        redis.opsForValue().set(GENERATION, "0");
        ReadCache large = new ReadCache(redis, JSON, "svc");
        ReadCache.Spec<Item> largeSpec = large.spec("item", Item.class, Duration.ofMinutes(10));
        PausingRedisTemplate template = new PausingRedisTemplate(connections, GENERATION::equals);
        ReadCache small = new ReadCache(template, JSON, "svc");
        ReadCache.Spec<Item> smallSpec = small.spec("item", Item.class, Duration.ofMinutes(10));
        AtomicInteger loads = new AtomicInteger();
        large.get(largeSpec, "products", "1", () -> load("snapshot-time", loads));
        Map<String, byte[]> snapshot = dump();   // 세대 0
        large.bumpNow("products");
        large.bumpNow("products");
        small.get(smallSpec, "products", "1", () -> load("v2", loads));   // 작은 쪽은 2 까지 봤다
        large.bumpNow("products");
        large.bumpNow("products");
        large.bumpNow("products");
        large.get(largeSpec, "products", "1", () -> load("v5", loads));   // 큰 쪽은 5 까지 봤다

        restore(snapshot);

        AtomicReference<Object> served = new AtomicReference<>();
        Thread smallReader = paused(template, served, () -> small.get(smallSpec, "products", "1", () -> load("small", loads)));   // 0 을 읽고, 올리기(3) 전에 멈춘다
        large.get(largeSpec, "products", "1", () -> load("large", loads));   // 큰 쪽이 6 으로 올린다
        assertThat(generation()).isEqualTo(6);
        resume(template, smallReader);

        assertThat(served.get()).as("멈췄던 조회도 끝까지 답했다").isInstanceOf(Item.class);
        assertThat(generation()).as("작은 쪽의 올리기(3)는 6 을 줄이지 않는다").isEqualTo(6);
    }

    /** 다른 스레드에서 조회를 시작해 세대 GET 직후 멈출 때까지 기다린다. 결과나 예외를 served 에 담는다 — 멈춘 뒤 던지는 구현을 놓치지 않게. */
    private static Thread paused(PausingRedisTemplate template, AtomicReference<Object> served, java.util.function.Supplier<Object> lookup) throws Exception {
        Thread thread = new Thread(() -> {
            template.pause.set(true);
            try {
                served.set(lookup.get());
            } catch (Throwable e) {
                served.set(e);
            }
        });
        thread.start();
        assertThat(template.read.await(10, TimeUnit.SECONDS)).as("세대 GET 에 이르렀다").isTrue();
        return thread;
    }

    @Test
    @DisplayName("밖에서 읽는 세대 번호(generation)는 조회가 키에 넣는 번호와 같다 — 올리면 늘고, 되돌아가면 본 번호보다 크게, 끈 캐시 · Redis 실패면 비어 있다")
    void generationMatchesWhatLookupsUse() {
        redis.opsForValue().set(GENERATION, "5");
        ReadCache cache = new ReadCache(redis, JSON, "svc");
        ReadCache.Spec<Item> spec = cache.spec("item", Item.class, Duration.ofMinutes(10));
        assertThat(cache.generation("products")).hasValue(5);
        cache.get(spec, "products", "1", () -> new Item("v"));
        assertThat(redis.keys("svc:cache:item:*")).singleElement().asString().contains(":products.5:");

        cache.bumpNow("products");
        assertThat(cache.generation("products")).as("올린 뒤").hasValue(6);

        redis.opsForValue().set(GENERATION, "2");   // 복원으로 되돌아갔다
        assertThat(cache.generation("products")).as("본 번호(6)보다 크게 올려 읽는다").hasValue(7);
        assertThat(generation()).isEqualTo(7);

        assertThat(ReadCache.off(redis, JSON, "svc").generation("products")).as("끈 캐시").isEmpty();
        cache.breaker().transitionToForcedOpenState();
        try {
            assertThat(cache.generation("products")).as("차단기 열림").isEmpty();
        } finally {
            cache.breaker().transitionToClosedState();
        }
        redis.opsForValue().set(GENERATION, "not-a-number");
        assertThat(cache.generation("products")).as("못 읽음").isEmpty();
    }

    @Test
    @DisplayName("마지막으로 본 세대 번호는 Redis 를 부르지 않는다 — 본 적 없으면 비어 있고, 읽은 뒤에는 그 번호, Redis 를 못 써도 그대로다")
    void lastSeenGenerationNeedsNoRedis() {
        redis.opsForValue().set(GENERATION, "5");
        ReadCache cache = new ReadCache(redis, JSON, "svc");
        assertThat(cache.lastSeenGeneration("products")).as("본 적 없음").isEmpty();
        assertThat(cache.generation("products")).hasValue(5);
        assertThat(cache.lastSeenGeneration("products")).hasValue(5);

        redis.opsForValue().set(GENERATION, "not-a-number");
        assertThat(cache.generation("products")).as("지금은 못 읽는다").isEmpty();
        assertThat(cache.lastSeenGeneration("products")).as("마지막으로 본 번호").hasValue(5);
    }

    @Test
    @DisplayName("밖에서 세대 번호를 읽을 때도 미룬 올리기를 먼저 한다 — 올리지 못한 세대가 복구 뒤 첫 읽기에서 올라 그 번호를 준다")
    void generationRetriesPendingBumps() {
        redis.opsForValue().set(GENERATION, "not-a-number");   // INCR 이 실패한다
        ReadCache cache = new ReadCache(redis, JSON, "svc");
        assertThatThrownBy(() -> cache.bumpNow("products")).as("못 올리면 던지고 미룬다").isInstanceOf(IllegalStateException.class);

        redis.opsForValue().set(GENERATION, "10");   // 복구
        assertThat(cache.generation("products")).as("미룬 올리기가 먼저 돈다").hasValue(11);
        assertThat(cache.generation("products")).as("한 번만").hasValue(11);
    }

    @Test
    @DisplayName("세대 여럿을 한 번에 읽어도(generations) 하나씩 읽는 것과 같다 — 없으면 만들고, 올리면 늘고, 되돌아가면 본 번호보다 크게, 끈 캐시 · Redis 실패 · 숫자 아님이면 비어 있다")
    void generationsMatchSingleReads() {
        redis.opsForValue().set(GENERATION, "5");
        ReadCache cache = new ReadCache(redis, JSON, "svc");
        ReadCache.Spec<Item> spec = cache.spec("item", Item.class, Duration.ofMinutes(10));
        long before = System.currentTimeMillis();
        ReadCache.Generations first = cache.generations("products", "campaigns");
        assertThat(first.number("products")).hasValue(5);
        assertThat(first.number("campaigns").getAsLong()).as("없는 세대는 지금 시각에서 시작").isGreaterThanOrEqualTo(before);
        cache.entry(spec, first, "products", "1", () -> new Item("v"));
        assertThat(redis.keys("svc:cache:item:*")).singleElement().asString().contains(":products.5:");
        assertThatThrownBy(() -> first.number("reviews")).as("읽지 않은 세대").isInstanceOf(IllegalArgumentException.class);

        cache.bumpNow("products");
        assertThat(first.number("products")).as("읽어 둔 묶음은 그 요청의 번호 그대로").hasValue(5);
        assertThat(cache.generations("products", "campaigns").number("products")).as("올린 뒤").hasValue(6);

        redis.opsForValue().set(GENERATION, "2");   // 복원으로 되돌아갔다
        assertThat(cache.generations("products", "campaigns").number("products")).as("본 번호(6)보다 크게 올려 읽는다").hasValue(7);
        assertThat(generation()).isEqualTo(7);

        assertThat(ReadCache.off(redis, JSON, "svc").generations("products").number("products")).as("끈 캐시").isEmpty();
        cache.breaker().transitionToForcedOpenState();
        try {
            assertThat(cache.generations("products", "campaigns").number("products")).as("차단기 열림").isEmpty();
        } finally {
            cache.breaker().transitionToClosedState();
        }
        redis.opsForValue().set(GENERATION, "not-a-number");
        ReadCache.Generations broken = cache.generations("products", "campaigns");
        assertThat(broken.number("products")).as("못 읽음").isEmpty();
        assertThat(broken.number("campaigns")).as("다른 세대는 읽힌다").isPresent();
    }

    @Test
    @DisplayName("세대 여럿을 읽을 때도 미룬 올리기를 먼저 한다 — 올린 세대는 올린 뒤 번호로 읽는다")
    void generationsRetryPendingBumps() {
        redis.opsForValue().set(GENERATION, "not-a-number");   // INCR 이 실패한다
        ReadCache cache = new ReadCache(redis, JSON, "svc");
        assertThatThrownBy(() -> cache.bumpNow("products")).isInstanceOf(IllegalStateException.class);

        redis.opsForValue().set(GENERATION, "10");   // 복구
        redis.opsForValue().set("svc:cache:gen:campaigns", "3");
        ReadCache.Generations read = cache.generations("campaigns", "products");   // 미룬 세대가 뒤에 있어도
        assertThat(read.number("products")).as("미룬 올리기가 먼저 돈다").hasValue(11);
        assertThat(read.number("campaigns")).hasValue(3);
        assertThat(cache.generations("campaigns", "products").number("products")).as("한 번만").hasValue(11);
    }

    @Test
    @DisplayName("세대 여럿은 Redis 왕복 하나(MGET)로 읽는다 — 세대마다 GET 하지 않는다")
    void generationsUseOneRoundTrip() {
        redis.opsForValue().set(GENERATION, "5");
        redis.opsForValue().set("svc:cache:gen:campaigns", "3");
        redis.opsForValue().set("svc:cache:gen:promotions", "7");
        ReadCache cache = new ReadCache(redis, JSON, "svc");
        java.util.Map<String, Long> before = commandCalls();

        ReadCache.Generations read = cache.generations("promotions", "products", "campaigns");

        java.util.Map<String, Long> after = commandCalls();
        assertThat(read.number("promotions")).hasValue(7);
        assertThat(after.getOrDefault("mget", 0L) - before.getOrDefault("mget", 0L)).as("MGET").isEqualTo(1);
        assertThat(after.getOrDefault("get", 0L) - before.getOrDefault("get", 0L)).as("GET").isZero();
        cache.generation("products");
        assertThat(commandCalls().getOrDefault("get", 0L) - after.getOrDefault("get", 0L)).as("대조군 — 하나씩 읽으면 GET").isEqualTo(1);
    }

    @Test
    @DisplayName("묶어 읽기는 차단기에 MGET 성공 하나만 센다 — 정상 값의 파싱은 세지 않고, 숫자가 아닌 값은 실패 하나로 센다")
    void generationsCountTheBreakerLikeSingleReads() {
        redis.opsForValue().set(GENERATION, "5");
        redis.opsForValue().set("svc:cache:gen:campaigns", "3");
        redis.opsForValue().set("svc:cache:gen:promotions", "7");
        ReadCache cache = new ReadCache(redis, JSON, "svc");
        var metrics = cache.breaker().getMetrics();
        int successful = metrics.getNumberOfSuccessfulCalls();
        int failed = metrics.getNumberOfFailedCalls();

        cache.generations("promotions", "products", "campaigns");
        assertThat(metrics.getNumberOfSuccessfulCalls() - successful).as("MGET 하나").isEqualTo(1);
        assertThat(metrics.getNumberOfFailedCalls() - failed).isZero();

        redis.opsForValue().set(GENERATION, "not-a-number");
        cache.generations("promotions", "products", "campaigns");
        assertThat(metrics.getNumberOfFailedCalls() - failed).as("숫자 아님은 실패 하나").isEqualTo(1);
    }

    /** Valkey 의 명령별 누적 호출 수(INFO commandstats). */
    private static java.util.Map<String, Long> commandCalls() {
        java.util.Map<String, Long> calls = new java.util.HashMap<>();
        try (RedisConnection connection = redis.getConnectionFactory().getConnection()) {
            java.util.Properties info = connection.serverCommands().info("commandstats");
            for (String name : info.stringPropertyNames()) {
                if (name.startsWith("cmdstat_")) {
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("calls=(\\d+)").matcher(info.getProperty(name));
                    if (m.find()) {
                        calls.put(name.substring("cmdstat_".length()), Long.parseLong(m.group(1)));
                    }
                }
            }
        }
        return calls;
    }

    private static void resume(PausingRedisTemplate template, Thread thread) throws InterruptedException {
        template.resume.countDown();
        thread.join(10_000);
        assertThat(thread.isAlive()).as("멈췄던 조회가 끝났다").isFalse();
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
