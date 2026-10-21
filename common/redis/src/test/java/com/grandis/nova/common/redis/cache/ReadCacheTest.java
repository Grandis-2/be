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
    @DisplayName("안쪽 새 트랜잭션(REQUIRES_NEW)의 올리기는 안쪽 커밋에 붙는다 — 바깥이 먼저 불렀어도 바깥 롤백에 같이 버려지지 않는다")
    void requiresNewBumpsOnItsOwnCommit() {
        TransactionTemplate outer = new TransactionTemplate(new NestingTransactionManager());
        TransactionTemplate inner = new TransactionTemplate(outer.getTransactionManager());
        inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        outer.executeWithoutResult(status -> {
            cache.bumpAfterCommit("reviews");
            inner.executeWithoutResult(nested -> cache.bumpAfterCommit("products"));
            assertThat(redis.opsForValue().get("svc:cache:gen:products")).as("안쪽 커밋 직후").isEqualTo("1");
            status.setRollbackOnly();
        });
        assertThat(redis.opsForValue().get("svc:cache:gen:products")).isEqualTo("1");
        assertThat(redis.opsForValue().get("svc:cache:gen:reviews")).as("롤백된 바깥은 올리지 않는다").isNull();
    }

    @Test
    @DisplayName("차단기가 열린 동안의 올리기는 버리지 않고 미룬다 — 닫힌 뒤 이 인스턴스의 첫 조회에서 올라 다른 인스턴스도 새 값을 읽는다")
    void bumpDeferredWhileBreakerOpen() {
        ReadCache other = new ReadCache(redis, JSON, "svc");
        ReadCache.Spec<Item> otherItems = other.spec("item", Item.class, Duration.ofSeconds(30));
        other.get(otherItems, "products", "1", () -> item("old", new AtomicInteger()));

        cache.breaker().transitionToForcedOpenState();
        cache.bumpAfterCommit("products");
        assertThat(redis.opsForValue().get("svc:cache:gen:products")).as("열린 동안은 부르지 않는다").isNull();
        cache.breaker().transitionToClosedState();

        cache.get(items, "reviews", "x", () -> item("x", new AtomicInteger()));   // 다른 세대의 조회여도 미룬 것을 올린다
        assertThat(redis.opsForValue().get("svc:cache:gen:products")).isEqualTo("1");
        assertThat(other.get(otherItems, "products", "1", () -> item("new", new AtomicInteger())).name()).isEqualTo("new");
    }

    @Test
    @DisplayName("미룬 뒤 다음 올리기가 성공하면 미룬 것은 지운다 — 다음 조회가 한 번 더 올리지 않는다")
    void successfulBumpClearsPending() {
        redis.opsForValue().set("svc:cache:gen:products", "not-a-number");
        cache.bumpAfterCommit("products");   // 실패 — 미룬다
        redis.delete("svc:cache:gen:products");
        cache.bumpAfterCommit("products");   // 성공

        cache.get(items, "products", "1", () -> item("v", new AtomicInteger()));
        assertThat(redis.opsForValue().get("svc:cache:gen:products")).isEqualTo("1");
    }

    @Test
    @DisplayName("미룬 올리기를 다시 해 보다 또 실패하면 다시 미룬다 — 그다음 조회에서 올린다")
    void failedRetryStaysPending() {
        redis.opsForValue().set("svc:cache:gen:products", "not-a-number");
        cache.bumpAfterCommit("products");
        cache.get(items, "reviews", "x", () -> item("x", new AtomicInteger()));   // 다른 세대 조회 — 다시 해 보지만 또 실패
        redis.delete("svc:cache:gen:products");

        cache.get(items, "reviews", "x", () -> item("x", new AtomicInteger()));
        assertThat(redis.opsForValue().get("svc:cache:gen:products")).isEqualTo("1");
    }

    @Test
    @DisplayName("복구 직후 여러 조회가 겹쳐도 미룬 세대는 한 번만 올린다")
    void pendingBumpRetriedOnceUnderConcurrency() throws Exception {
        redis.opsForValue().set("svc:cache:gen:products", "not-a-number");
        cache.bumpAfterCommit("products");
        redis.delete("svc:cache:gen:products");

        concurrently(32, () -> cache.get(items, "reviews", "x", () -> item("x", new AtomicInteger())));
        assertThat(redis.opsForValue().get("svc:cache:gen:products")).isEqualTo("1");
    }

    @Test
    @DisplayName("한 트랜잭션에서 두 캐시(서비스)가 같은 이름의 세대를 올리면 각자 올린다 — 서로의 커밋 콜백에 섞이지 않는다")
    void twoCachesInOneTransactionBumpSeparately() {
        ReadCache other = new ReadCache(redis, JSON, "other");
        new TransactionTemplate(new NoOpTransactionManager()).executeWithoutResult(status -> {
            cache.bumpAfterCommit("products");
            other.bumpAfterCommit("products");
        });
        assertThat(redis.opsForValue().get("svc:cache:gen:products")).isEqualTo("1");
        assertThat(redis.opsForValue().get("other:cache:gen:products")).isEqualTo("1");
    }

    @Test
    @DisplayName("올리지 못한 세대는 미뤄 두었다가 Redis 를 다시 읽는 첫 조회에서 올린다 — 그 조회부터 새 값")
    void failedBumpIsRetriedOnRecovery() {
        cache.get(items, "products", "1", () -> item("old", new AtomicInteger()));
        // INCR 이 확실히 실패하게(정수가 아닌 값). 멈춘 Redis(PAUSE)로는 못 잰다 — 시간 초과된 INCR 이 풀린 뒤 서버에서 실행돼 세대가 올라 버린다
        redis.opsForValue().set("svc:cache:gen:products", "not-a-number");
        cache.bumpAfterCommit("products");   // 던지지 않는다
        redis.delete("svc:cache:gen:products");   // 복구 — 세대는 다시 0 이고 옛 값(0 세대 키)이 그대로 있다

        assertThat(cache.get(items, "products", "1", () -> item("new", new AtomicInteger())).name()).isEqualTo("new");
        assertThat(redis.opsForValue().get("svc:cache:gen:products")).isEqualTo("1");
        cache.get(items, "products", "1", () -> item("newer", new AtomicInteger()));
        assertThat(redis.opsForValue().get("svc:cache:gen:products")).as("한 번만 올린다").isEqualTo("1");
    }

    @Test
    @DisplayName("bumpNow 는 트랜잭션 안에서도 바로 올리고, 못 올리면 던지면서 미뤄도 둔다 — 복구 뒤 이 인스턴스의 첫 조회가 올린다. 끈 캐시도 Redis 가 있으면 올린다")
    void bumpNowThrowsAndDefers() {
        new TransactionTemplate(new NoOpTransactionManager()).executeWithoutResult(status -> {
            cache.bumpNow("products");
            assertThat(redis.opsForValue().get("svc:cache:gen:products")).as("커밋을 기다리지 않는다").isEqualTo("1");
        });

        redis.opsForValue().set("svc:cache:gen:products", "not-a-number");
        assertThatThrownBy(() -> cache.bumpNow("products")).isInstanceOf(IllegalStateException.class);
        redis.delete("svc:cache:gen:products");
        cache.get(items, "reviews", "x", () -> item("x", new AtomicInteger()));
        assertThat(redis.opsForValue().get("svc:cache:gen:products")).as("미룬 것").isEqualTo("1");

        cache.breaker().transitionToForcedOpenState();
        assertThatThrownBy(() -> cache.bumpNow("products")).as("차단기가 열려도 던진다").isInstanceOf(IllegalStateException.class);
        cache.breaker().transitionToClosedState();

        ReadCache.off(JSON, "svc").bumpNow("off");
        assertThat(redis.opsForValue().get("svc:cache:gen:off")).as("Redis 없는 끈 캐시는 아무것도 안 한다").isNull();
        ReadCache.off(redis, JSON, "svc").bumpNow("off");
        assertThat(redis.opsForValue().get("svc:cache:gen:off")).as("Redis 를 받은 끈 캐시는 올린다 — 같은 큐의 켠 인스턴스를 위해").isEqualTo("1");

        ReadCache offWithRedis = ReadCache.off(redis, JSON, "offsvc");
        ReadCache.Spec<Item> offItems = offWithRedis.spec("item", Item.class, Duration.ofSeconds(30));
        AtomicInteger loads = new AtomicInteger();
        offWithRedis.get(offItems, "products", "1", () -> item("v", loads));
        offWithRedis.get(offItems, "products", "1", () -> item("v", loads));
        offWithRedis.bumpAfterCommit("products");
        assertThat(loads).as("조회는 늘 loader").hasValue(2);
        assertThat(redis.keys("offsvc:cache:*")).as("조회 · 커밋 뒤 올리기는 Redis 를 부르지 않는다").isEmpty();
    }

    @Test
    @DisplayName("빈 캐시 · 끈 캐시 — loader 는 채우기(FILL)를 받아 넣고, 끈 캐시는 우회(BYPASS)를 받는다")
    void loadPathFillAndOff() {
        List<ReadCache.LoadPath> paths = new java.util.ArrayList<>();
        cache.get(items, "products", "1", path -> { paths.add(path); return item("v", new AtomicInteger()); });
        assertThat(paths).containsExactly(ReadCache.LoadPath.FILL);
        assertThat(redis.keys("svc:cache:item:*")).hasSize(1);

        ReadCache.off(redis, JSON, "offsvc").get(items, "products", "1", path -> { paths.add(path); return item("v", new AtomicInteger()); });
        assertThat(paths).containsExactly(ReadCache.LoadPath.FILL, ReadCache.LoadPath.BYPASS);
    }

    @Test
    @DisplayName("세대를 못 읽으면 우회 — loader 는 BYPASS 를 받고 넣지 않는다. 그 loader 가 도는 사이 Redis 가 돌아와도 넣지 않는다")
    void generationReadFailureBypassesEvenIfRedisRecovers() {
        redis.opsForValue().set("svc:cache:gen:products", "not-a-number");   // GET 은 되지만 번호로 못 읽는다 — 세대 조회 실패
        List<ReadCache.LoadPath> paths = new java.util.ArrayList<>();
        cache.get(items, "products", "1", path -> {
            paths.add(path);
            redis.delete("svc:cache:gen:products");   // 우회 조회 도중 복구
            return item("v", new AtomicInteger());
        });
        assertThat(paths).containsExactly(ReadCache.LoadPath.BYPASS);
        assertThat(redis.keys("svc:cache:item:*")).as("우회 결과는 넣지 않는다").isEmpty();

        cache.get(items, "products", "1", path -> { paths.add(path); return item("v", new AtomicInteger()); });
        assertThat(paths).as("복구 뒤 다음 조회는 채우기").containsExactly(ReadCache.LoadPath.BYPASS, ReadCache.LoadPath.FILL);
    }

    @Test
    @DisplayName("세대는 읽었는데 값을 못 읽으면 우회 — 없음으로 보지 않고 우회한다")
    void valueReadFailureBypasses() {
        cache.get(items, "products", "1", () -> item("v", new AtomicInteger()));
        String key = onlyKey("svc:cache:item:*");
        redis.delete(key);
        redis.opsForList().rightPush(key, "x");   // 같은 키를 목록으로 — GET 이 WRONGTYPE 으로 실패한다

        List<ReadCache.LoadPath> paths = new java.util.ArrayList<>();
        cache.get(items, "products", "1", path -> { paths.add(path); return item("v", new AtomicInteger()); });
        assertThat(paths).containsExactly(ReadCache.LoadPath.BYPASS);
        assertThat(redis.type(key).code()).as("우회 결과로 덮지 않는다").isEqualTo("list");
    }

    @Test
    @DisplayName("세대를 읽은 뒤 값을 읽기 직전에 차단기가 열려도 우회 — 없음으로 보지 않고 우회한다")
    void breakerOpeningBeforeValueReadBypasses() {
        StringRedisTemplate spied = org.mockito.Mockito.spy(redis);
        org.springframework.data.redis.core.ValueOperations<String, String> ops = org.mockito.Mockito.spy(redis.opsForValue());
        org.mockito.Mockito.doReturn(ops).when(spied).opsForValue();
        ReadCache opening = new ReadCache(spied, JSON, "svc");
        ReadCache.Spec<Item> openingItems = opening.spec("item", Item.class, Duration.ofSeconds(30));
        org.mockito.Mockito.doAnswer(call -> {
            Object value = call.callRealMethod();
            if (((String) call.getArgument(0)).contains(":gen:")) {
                opening.breaker().transitionToForcedOpenState();   // 세대는 읽었다 — 값 읽기는 차단기에 막힌다
            }
            return value;
        }).when(ops).get(org.mockito.ArgumentMatchers.anyString());

        List<ReadCache.LoadPath> paths = new java.util.ArrayList<>();
        opening.get(openingItems, "products", "1", path -> { paths.add(path); return item("v", new AtomicInteger()); });
        opening.breaker().transitionToClosedState();

        assertThat(paths).as("가르는 단언 — 저장은 채우기여도 열린 차단기가 막아 비어 있다").containsExactly(ReadCache.LoadPath.BYPASS);
    }

    @Test
    @DisplayName("차단기가 열려 있으면 우회 — loader 는 BYPASS 를 받고 넣지 않는다")
    void openBreakerBypasses() {
        cache.breaker().transitionToForcedOpenState();
        List<ReadCache.LoadPath> paths = new java.util.ArrayList<>();
        cache.get(items, "products", "1", path -> { paths.add(path); return item("v", new AtomicInteger()); });
        cache.breaker().transitionToClosedState();

        assertThat(paths).containsExactly(ReadCache.LoadPath.BYPASS);
        assertThat(redis.keys("svc:cache:item:*")).isEmpty();
    }

    @Test
    @DisplayName("세대가 오르면 이 인스턴스가 옛 세대로 넣은 키를 지우고 저장 몫을 돌려준다 — 예산이 찬 캐시도 새 세대 값을 바로 넣는다")
    void bumpReleasesOldGenerationBudget() {
        ReadCache.Spec<Item> small = cache.spec("small", Item.class, Duration.ofSeconds(30), 500);   // 값 하나(약 360)만 들어간다
        cache.get(small, "products", "a", () -> item("a", new AtomicInteger()));
        cache.get(small, "reviews", "r", () -> item("r", new AtomicInteger()));   // 예산이 차서 안 들어간다 — 다른 세대 이름
        assertThat(redis.keys("svc:cache:small:*")).hasSize(1);

        cache.bumpAfterCommit("products");
        AtomicInteger loads = new AtomicInteger();
        cache.get(small, "products", "b", () -> item("b", loads));
        cache.get(small, "products", "b", () -> item("b", loads));

        assertThat(loads).as("두 번째는 캐시에서").hasValue(1);
        Set<String> keys = redis.keys("svc:cache:small:*");
        assertThat(keys).hasSize(1);
        assertThat(keys.iterator().next()).contains(":products.1:");
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
            cache.bumpAfterCommit("products");   // 열린 동안은 부르지 않고 미룬다 — 커밋 뒤 콜백이 DB 커넥션을 쥔 채 기다리지 않게
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

    /** 바깥 트랜잭션을 알아보고 REQUIRES_NEW 에서 바깥 동기화를 떼어 두는 관리자 — DB 없이 안쪽 커밋을 재려고. */
    static final class NestingTransactionManager extends AbstractPlatformTransactionManager {
        private final ThreadLocal<Integer> depth = ThreadLocal.withInitial(() -> 0);

        @Override
        protected Object doGetTransaction() {
            return depth.get();
        }

        @Override
        protected boolean isExistingTransaction(Object transaction) {
            return (Integer) transaction > 0;
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            depth.set(depth.get() + 1);
        }

        @Override
        protected Object doSuspend(Object transaction) {
            return transaction;
        }

        @Override
        protected void doResume(Object transaction, Object suspendedResources) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }

        @Override
        protected void doSetRollbackOnly(DefaultTransactionStatus status) {
        }

        @Override
        protected void doCleanupAfterCompletion(Object transaction) {
            depth.set(depth.get() - 1);
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
