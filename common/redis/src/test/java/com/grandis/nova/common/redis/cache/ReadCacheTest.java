package com.grandis.nova.common.redis.cache;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReadCacheTest {

    /** 운영과 같은 Valkey 9 — 메모리 계산 기준(StoreBudget.charge)을 이 엔진으로 잰다. */
    private static final GenericContainer<?> REDIS = new GenericContainer<>("valkey/valkey:9").withExposedPorts(6379);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    static LettuceConnectionFactory connections;
    static StringRedisTemplate redis;

    ReadCache cache;
    ReadCache.Spec<Item> items;

    record Item(UUID id, String name, BigDecimal price, Instant at, Kind kind, List<String> tags) {
    }

    enum Kind { A, B }

    record ItemV1(String name) {
    }

    record ItemV2(String name, String added) {
    }

    record Page<T>(int page, List<T> items) {
    }

    @BeforeAll
    static void start() {
        REDIS.start();
        RedisStandaloneConfiguration server = new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379));
        // 운영 예시 설정과 같은 제한 시간 — 멈춘 Redis 에서 한 번에 이만큼 기다린다
        connections = new LettuceConnectionFactory(server, LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(300)).build());
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
    void setUp() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        cache = new ReadCache(redis, JSON, "svc");
        items = cache.spec("item", Item.class, Duration.ofSeconds(30));
    }

    @Test
    @DisplayName("없으면 loader 로 채우고, 다음 조회는 loader 없이 같은 값 — JSON 을 거쳐도 금액 · 시각 · enum · 목록이 그대로다")
    void missThenHit() {
        AtomicInteger loads = new AtomicInteger();
        Item item = new Item(UUID.randomUUID(), "폰", new BigDecimal("2118600"), Instant.parse("2026-10-14T01:02:03.123456Z"), Kind.B, List.of("a", "b"));

        assertThat(cache.get(items, "products", "1", () -> { loads.incrementAndGet(); return item; })).isEqualTo(item);
        assertThat(cache.get(items, "products", "1", () -> { loads.incrementAndGet(); return null; })).isEqualTo(item);
        assertThat(loads).hasValue(1);
        assertThat(redis.getExpire(onlyKey("svc:cache:item:*"))).isBetween(25L, 30L);
    }

    @Test
    @DisplayName("세대를 올리면 그 세대의 값은 다음 조회부터 새로 채운다 — 다른 세대 이름의 값은 그대로")
    void bumpInvalidatesOnlyThatGeneration() {
        AtomicInteger loads = new AtomicInteger();
        cache.get(items, "products", "1", () -> item("v1", loads));
        cache.get(items, "reviews", "1", () -> item("r1", loads));

        cache.bumpAfterCommit("products");

        assertThat(cache.get(items, "products", "1", () -> item("v2", loads)).name()).isEqualTo("v2");
        assertThat(cache.get(items, "reviews", "1", () -> item("r2", loads)).name()).isEqualTo("r1");
        assertThat(loads).hasValue(3);
    }

    @Test
    @DisplayName("트랜잭션 안의 올리기는 커밋 뒤에만 — 커밋 전에는 옛 값, 롤백이면 올리지 않는다")
    void bumpWaitsForCommit() {
        TransactionTemplate tx = new TransactionTemplate(new NoOpTransactionManager());
        cache.get(items, "products", "1", () -> item("old", new AtomicInteger()));

        tx.executeWithoutResult(status -> {
            cache.bumpAfterCommit("products");
            assertThat(cache.get(items, "products", "1", () -> item("inside", new AtomicInteger())).name()).isEqualTo("old");
            status.setRollbackOnly();
        });
        assertThat(cache.get(items, "products", "1", () -> item("after-rollback", new AtomicInteger())).name()).isEqualTo("old");

        tx.executeWithoutResult(status -> {
            cache.bumpAfterCommit("products");
            cache.bumpAfterCommit("products");
            cache.bumpAfterCommit("reviews");
        });
        assertThat(cache.get(items, "products", "1", () -> item("after-commit", new AtomicInteger())).name()).isEqualTo("after-commit");
        // 한 트랜잭션에서 여러 번 불러도 세대마다 한 번 올린다
        assertThat(redis.opsForValue().get("svc:cache:gen:products")).isEqualTo("1");
        assertThat(redis.opsForValue().get("svc:cache:gen:reviews")).isEqualTo("1");
    }

    @Test
    @DisplayName("같은 키를 동시에 20개가 찾으면 loader 는 한 번 — 나머지는 그 결과를 받는다. loader 의 예외도 같이 받는다")
    void concurrentMissesLoadOnce() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        List<Item> results = concurrently(20, () -> cache.get(items, "products", "hot", () -> {
            loads.incrementAndGet();
            sleep(300);
            return item("hot", new AtomicInteger());
        }));
        assertThat(loads).hasValue(1);
        assertThat(results).extracting(Item::name).containsOnly("hot");

        AtomicInteger failures = new AtomicInteger();
        List<Item> failed = concurrently(10, () -> {
            try {
                return cache.get(items, "products", "missing", () -> {
                    failures.incrementAndGet();
                    sleep(300);
                    throw new IllegalStateException("not found");
                });
            } catch (IllegalStateException e) {
                return null;
            }
        });
        assertThat(failures).hasValue(1);
        assertThat(failed).containsOnlyNulls();
    }

    @Test
    @DisplayName("세대가 오른 뒤 들어온 요청은 옛 세대의 진행 중 조회를 같이 받지 않는다 — 새 세대로 다시 읽는다")
    void requestAfterBumpDoesNotShareOldGenerationLoad() throws Exception {
        CountDownLatch oldLoadStarted = new CountDownLatch(1);
        CountDownLatch releaseOldLoad = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Item> old = pool.submit(() -> cache.get(items, "products", "1", () -> {
                oldLoadStarted.countDown();
                await(releaseOldLoad);
                return item("before-edit", new AtomicInteger());
            }));
            oldLoadStarted.await();
            cache.bumpAfterCommit("products");   // 관리자 수정이 커밋됐다

            CompletableFuture<Item> fresh = CompletableFuture.supplyAsync(
                    () -> cache.get(items, "products", "1", () -> item("after-edit", new AtomicInteger())));
            Item freshItem = fresh.get(5, java.util.concurrent.TimeUnit.SECONDS);
            releaseOldLoad.countDown();

            assertThat(freshItem.name()).isEqualTo("after-edit");
            assertThat(old.get().name()).isEqualTo("before-edit");
        } finally {
            releaseOldLoad.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("모양이 다른 타입은 같은 이름이어도 서로의 값을 읽지 않는다 — 칸이 는 새 버전이 옛 값을 읽어 새 칸을 null 로 내보내지 않게")
    void differentShapeUsesDifferentKey() {
        ReadCache.Spec<ItemV1> v1 = cache.spec("shape", ItemV1.class, Duration.ofSeconds(30));
        ReadCache.Spec<ItemV2> v2 = cache.spec("shape", ItemV2.class, Duration.ofSeconds(30));
        cache.get(v1, "products", "1", () -> new ItemV1("old"));

        assertThat(cache.get(v2, "products", "1", () -> new ItemV2("new", "x"))).isEqualTo(new ItemV2("new", "x"));
        assertThat(v1.fingerprint()).isNotEqualTo(v2.fingerprint());

        ReadCache.Spec<Page<ItemV1>> pageOfV1 = cache.spec("page", new TypeReference<Page<ItemV1>>() { }, Duration.ofSeconds(30));
        ReadCache.Spec<Page<ItemV2>> pageOfV2 = cache.spec("page", new TypeReference<Page<ItemV2>>() { }, Duration.ofSeconds(30));
        assertThat(pageOfV1.fingerprint()).as("타입 인자의 모양도 지문에 들어간다").isNotEqualTo(pageOfV2.fingerprint());
        assertThat(cache.spec("e", Item.class, Duration.ofSeconds(1)).fingerprint()).isEqualTo(items.fingerprint());
    }

    @Test
    @DisplayName("지문이 같은데 못 읽는 값(깨진 JSON)은 없는 것으로 보고 다시 채운다")
    void unreadableValueIsReloaded() {
        cache.get(items, "products", "1", () -> item("v1", new AtomicInteger()));
        redis.opsForValue().set(onlyKey("svc:cache:item:*"), "{not json");

        assertThat(cache.get(items, "products", "1", () -> item("v2", new AtomicInteger())).name()).isEqualTo("v2");
        assertThat(cache.get(items, "products", "1", () -> item("v3", new AtomicInteger())).name()).isEqualTo("v2");
    }

    @Test
    @DisplayName("Redis 가 멈추면 캐시 없이 loader 로 답하고(예외 없음), 실패가 이어지면 차단기가 열려 Redis 를 기다리지 않는다 — 다른 id 끼리 섞이지 않는다")
    void redisDownFallsBackToLoaderAndStopsWaiting() {
        cache.get(items, "products", "1", () -> item("cached", new AtomicInteger()));
        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
        try {
            long start = System.nanoTime();
            assertThat(cache.get(items, "products", "1", () -> item("db-1", new AtomicInteger())).name()).isEqualTo("db-1");
            long firstMillis = (System.nanoTime() - start) / 1_000_000;
            assertThat(firstMillis).as("열리기 전에는 제한 시간만큼 기다린다").isGreaterThanOrEqualTo(250);

            for (int i = 0; i < 5 && cache.breakerState() != CircuitBreaker.State.OPEN; i++) {
                cache.get(items, "products", "x" + i, () -> item("db", new AtomicInteger()));
            }
            assertThat(cache.breakerState()).isEqualTo(CircuitBreaker.State.OPEN);

            start = System.nanoTime();
            Item a = cache.get(items, "products", "a", () -> item("db-a", new AtomicInteger()));
            Item b = cache.get(items, "products", "b", () -> item("db-b", new AtomicInteger()));
            cache.bumpAfterCommit("products");
            long openMillis = (System.nanoTime() - start) / 1_000_000;
            assertThat(a.name()).isEqualTo("db-a");
            assertThat(b.name()).isEqualTo("db-b");
            assertThat(openMillis).as("열린 뒤에는 Redis 를 부르지 않는다").isLessThan(100);
        } finally {
            REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
        }
    }

    @Test
    @DisplayName("같은 키 조회를 기다리는 쪽은 상한까지만 — 넘으면 503 이고 loader 를 새로 부르지 않는다. 진행 중 조회는 그대로 끝난다")
    void waitersGiveUpAfterTheCap() throws Exception {
        ReadCache shortWait = new ReadCache(redis, JSON, "svc", io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.ofDefaults(), true,
                java.time.Clock.systemUTC(), Duration.ofMillis(300));
        ReadCache.Spec<Item> spec = shortWait.spec("item", Item.class, Duration.ofSeconds(30));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger loads = new AtomicInteger();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Item> first = pool.submit(() -> shortWait.get(spec, "products", "slow", () -> {
                loads.incrementAndGet();
                started.countDown();
                await(release);
                return item("slow", new AtomicInteger());
            }));
            started.await();
            long start = System.nanoTime();
            assertThatThrownBy(() -> shortWait.get(spec, "products", "slow", () -> {
                loads.incrementAndGet();
                return item("second", new AtomicInteger());
            })).isInstanceOf(com.grandis.nova.common.BusinessException.class)
                    .satisfies(e -> assertThat(((com.grandis.nova.common.BusinessException) e).errorCode())
                            .isEqualTo(com.grandis.nova.common.CommonErrorCode.DEPENDENCY_UNAVAILABLE));
            assertThat((System.nanoTime() - start) / 1_000_000).isBetween(250L, 2_000L);
            release.countDown();
            assertThat(first.get().name()).isEqualTo("slow");
            assertThat(loads).hasValue(1);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("값을 읽은 시각이 같이 저장된다 — 남은 시간(HTTP max-age 상한)은 TTL 에서 나이를 뺀 값, 넘으면 0")
    void entryCarriesLoadedAt() throws Exception {
        ReadCache.Entry<Item> loaded = cache.entry(items, "products", "1", () -> item("v", new AtomicInteger()));
        Thread.sleep(1100);
        ReadCache.Entry<Item> hit = cache.entry(items, "products", "1", () -> item("other", new AtomicInteger()));

        assertThat(hit.value().name()).isEqualTo("v");
        assertThat(hit.loadedAt()).isEqualTo(loaded.loadedAt().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
        Duration remaining = hit.remaining(Duration.ofSeconds(30), Instant.now());
        assertThat(remaining).isBetween(Duration.ofSeconds(27), Duration.ofSeconds(29));
        assertThat(hit.remaining(Duration.ofSeconds(1), Instant.now())).isEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("Redis 가 돌아오면 차단기가 열린 시간 뒤 시험 호출로 다시 닫히고 캐시를 다시 쓴다")
    void breakerClosesAfterRecovery() throws Exception {
        ReadCache quick = new ReadCache(redis, JSON, "svc", io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.custom()
                .slidingWindowSize(4).minimumNumberOfCalls(2).failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofMillis(500)).permittedNumberOfCallsInHalfOpenState(2).build(),
                true, java.time.Clock.systemUTC(), Duration.ofSeconds(5));
        ReadCache.Spec<Item> spec = quick.spec("item", Item.class, Duration.ofSeconds(30));
        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
        try {
            for (int i = 0; i < 3 && quick.breakerState() != CircuitBreaker.State.OPEN; i++) {
                quick.get(spec, "products", "x" + i, () -> item("db", new AtomicInteger()));
            }
            assertThat(quick.breakerState()).isEqualTo(CircuitBreaker.State.OPEN);
        } finally {
            REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
        }
        Thread.sleep(600);
        AtomicInteger loads = new AtomicInteger();
        quick.get(spec, "products", "back", () -> item("back", loads));
        quick.get(spec, "products", "back", () -> item("again", loads));
        assertThat(quick.breakerState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(loads).as("닫힌 뒤 두 번째는 캐시에서").hasValue(1);
    }

    @Test
    @DisplayName("저장 상한을 넘는 값은 넣지 않고 loader 로 답한다 — 상한은 키 · 값 · 관리 비용을 합친 양으로 센다")
    void budgetCapsWhatIsStored() {
        ReadCache.Spec<Item> small = cache.spec("small", Item.class, Duration.ofSeconds(30), 500);
        AtomicInteger loads = new AtomicInteger();
        cache.get(small, "products", "a", () -> item("a", loads));
        cache.get(small, "products", "b", () -> item("b", loads));   // 값 하나가 약 360 바이트로 세어져 두 번째는 넘는다
        assertThat(redis.keys("svc:cache:small:*")).hasSize(1);
        assertThat(small.budget().used()).isBetween(250L, 500L);
        cache.get(small, "products", "b", () -> item("b", loads));
        assertThat(loads).as("넣지 못한 b 는 다시 DB").hasValue(3);
    }

    @Test
    @DisplayName("만료 전 값은 계속 센다 — 예산 400 · 10초 잡기에서 0초 400 을 잡으면 9초의 360 은 거절, 10초 뒤에는 받는다(토큰 버킷이면 받았다)")
    void liveBytesStayCountedUntilExpiry() {
        java.util.concurrent.atomic.AtomicLong nanos = new java.util.concurrent.atomic.AtomicLong();
        ReadCache.StoreBudget budget = new ReadCache.StoreBudget(400, nanos::get);
        assertThat(budget.reserve(400, Duration.ofSeconds(10))).isNotNull();
        nanos.addAndGet(Duration.ofSeconds(9).toNanos());
        assertThat(budget.reserve(360, Duration.ofSeconds(10))).isNull();
        nanos.addAndGet(Duration.ofSeconds(1).toNanos());
        assertThat(budget.reserve(360, Duration.ofSeconds(10))).isNotNull();
        assertThat(budget.used()).isEqualTo(360);
    }

    @Test
    @DisplayName("nanoTime 이 음수이거나 만료 시각이 넘쳐도 잡은 몫이 바로 풀리지 않는다 — 차이로 비교한다")
    void negativeNanoTimeKeepsHolds() {
        java.util.concurrent.atomic.AtomicLong nanos = new java.util.concurrent.atomic.AtomicLong(-1);
        ReadCache.StoreBudget budget = new ReadCache.StoreBudget(400, nanos::get);
        assertThat(budget.reserve(400, Duration.ofSeconds(10))).isNotNull();
        assertThat(budget.reserve(400, Duration.ofSeconds(10))).isNull();
        nanos.set(Long.MIN_VALUE + 5);   // 아주 작은 음수에서 시작한 경우
        ReadCache.StoreBudget fromMin = new ReadCache.StoreBudget(400, nanos::get);
        assertThat(fromMin.reserve(400, Duration.ofSeconds(10))).isNotNull();
        assertThat(fromMin.reserve(1, Duration.ofSeconds(10))).isNull();
        nanos.addAndGet(Duration.ofSeconds(10).toNanos());
        assertThat(fromMin.reserve(400, Duration.ofSeconds(10))).isNotNull();

        nanos.set(Long.MAX_VALUE - 5);   // 만료 시각이 넘쳐 음수가 되는 자리
        ReadCache.StoreBudget nearMax = new ReadCache.StoreBudget(400, nanos::get);
        assertThat(nearMax.reserve(400, Duration.ofSeconds(10))).isNotNull();
        assertThat(nearMax.reserve(1, Duration.ofSeconds(10))).isNull();
    }

    @Test
    @DisplayName("값은 절대 만료 시각(PXAT)으로 들어간다 — 만료 시각이 넣은 때의 벽시계 + 남은 TTL 이라 서버가 늦게 처리해도 늘지 않는다")
    void storedWithAbsoluteExpiry() throws Exception {
        long before = System.currentTimeMillis();
        cache.get(items, "products", "abs", () -> item("abs", new AtomicInteger()));
        long after = System.currentTimeMillis();
        String key = onlyKey("svc:cache:item:*abs");
        long expiresAt = Long.parseLong(REDIS.execInContainer("valkey-cli", "PEXPIRETIME", key).getStdout().strip());
        assertThat(expiresAt).isBetween(before + 29_000, after + 30_000);
    }

    @Test
    @DisplayName("서버가 SET 을 늦게 처리해도(CLIENT PAUSE WRITE) 만료 시각은 넣으려던 때 기준 — 늦은 만큼 더 살지 않는다")
    void lateExecutedSetDoesNotLiveLonger() throws Exception {
        REDIS.execInContainer("valkey-cli", "CLIENT", "PAUSE", "1500", "WRITE");   // 읽기는 되고 쓰기만 1.5초 멈춘다
        long before = System.currentTimeMillis();
        cache.get(items, "products", "late", () -> item("late", new AtomicInteger()));   // SET 은 300ms 에 시간 초과
        // 멈춤이 풀리고 서버가 밀린 SET 을 처리할 때까지 기다린다(고정 시간이 아니라 키가 생길 때까지 — 느린 CI 에서도).
        // 앱 연결에서는 밀린 SET 뒤에 줄을 서므로 다른 연결(valkey-cli)로 본다
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (REDIS.execInContainer("valkey-cli", "--scan", "--pattern", "svc:cache:item:*late").getStdout().isBlank()
                && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        String key = onlyKey("svc:cache:item:*late");
        long expiresAt = Long.parseLong(REDIS.execInContainer("valkey-cli", "PEXPIRETIME", key).getStdout().strip());
        assertThat(expiresAt).as("상대 TTL(PX)이면 처리 시각(+1.5초)부터 30초").isLessThanOrEqualTo(before + 30_000 + 500);
    }

    @Test
    @DisplayName("저장 직전에 차단기가 열려 보내지 않았으면 몫을 바로 돌려준다")
    void releasedWhenBreakerBlocksTheWrite() {
        ReadCache.Spec<Item> spec = cache.spec("blocked", Item.class, Duration.ofSeconds(30), 10_000);
        Item value = cache.get(spec, "products", "x", () -> {
            cache.breaker().transitionToForcedOpenState();   // 읽는 사이 차단기가 열렸다
            return item("x", new AtomicInteger());
        });
        assertThat(value.name()).isEqualTo("x");
        assertThat(spec.budget().used()).isZero();
        cache.breaker().transitionToClosedState();
        assertThat(redis.keys("svc:cache:blocked:*")).isEmpty();
    }

    @Test
    @DisplayName("셀 양(charge)은 Valkey 의 MEMORY USAGE 보다 늘 크다 — 작은 값(키마다 고정 비용)부터 큰 값(할당 단위)까지")
    void chargeIsAtLeastActualMemory() throws Exception {
        String key = "catalog:cache:product-detail:f1.3fa2c1d0e9ab:products.12:0199a3f2-8a11-7c22-8d33-5e6f70819203";
        for (int size : new int[] {1, 50, 200, 1_000, 5_000, 20_000, 60_000, 200_000, 300_000, 1_000_000}) {
            String value = "가".repeat(size / 3) + "x".repeat(size % 3);
            redis.opsForValue().set(key, value, Duration.ofMinutes(10));
            long actual = Long.parseLong(REDIS.execInContainer("valkey-cli", "MEMORY", "USAGE", key).getStdout().strip());
            int valueBytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            assertThat(ReadCache.StoreBudget.charge(key.length(), valueBytes)).as("value %d bytes, actual %d", valueBytes, actual)
                    .isGreaterThanOrEqualTo(actual);
        }
    }

    @Test
    @DisplayName("넣을지 정하는 조건(빈 결과 등)이 false 면 넣지 않는다 · 캐시하지 않는 조회도 시작 시각을 읽은 시각으로 준다")
    void storablePredicateAndUncachedStartTime() {
        AtomicInteger loads = new AtomicInteger();
        cache.entry(items, "products", "empty", () -> item("e", loads), value -> false);
        cache.entry(items, "products", "empty", () -> item("e", loads), value -> false);
        assertThat(loads).hasValue(2);
        assertThat(redis.keys("svc:cache:item:*")).isEmpty();

        Instant before = Instant.now();
        ReadCache.Entry<Item> direct = cache.uncached(() -> {
            sleep(200);
            return item("d", loads);
        });
        assertThat(direct.loadedAt()).isBetween(before, before.plusMillis(150));
    }

    @Test
    @DisplayName("TTL 은 0 보다 커야 한다")
    void ttlMustBePositive() {
        assertThatThrownBy(() -> cache.spec("x", Item.class, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }

    private static Item item(String name, AtomicInteger loads) {
        loads.incrementAndGet();
        return new Item(UUID.nameUUIDFromBytes(name.getBytes()), name, BigDecimal.TEN, Instant.EPOCH, Kind.A, List.of());
    }

    private static String onlyKeyMatching(String pattern) {
        return onlyKey(pattern);
    }

    private static String onlyKey(String pattern) {
        Set<String> keys = redis.keys(pattern);
        assertThat(keys).hasSize(1);
        return keys.iterator().next();
    }

    private static <T> List<T> concurrently(int threads, java.util.concurrent.Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return task.call();
                }));
            }
            ready.await();
            go.countDown();
            List<T> out = new ArrayList<>();
            for (Future<T> future : futures) {
                out.add(future.get());
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static final class MutableClock extends java.time.Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public java.time.ZoneId getZone() {
            return java.time.ZoneOffset.UTC;
        }

        @Override
        public java.time.Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** 커밋 · 롤백 때 동기화 콜백만 돌리는 트랜잭션 관리자 — DB 없이 "커밋 뒤" 를 재려고. */
    static final class NoOpTransactionManager extends AbstractPlatformTransactionManager {
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
