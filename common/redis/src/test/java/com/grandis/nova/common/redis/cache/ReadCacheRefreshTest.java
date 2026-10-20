package com.grandis.nova.common.redis.cache;

import com.grandis.nova.common.redis.cache.ReadCacheEvents.Outcome;
import com.grandis.nova.common.redis.cache.ReadCacheEvents.RefreshResult;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Redis 읽기 캐시의 미리 갱신. 갱신 실행기는 맡긴 일을 모아 두었다가 시험이 정한 때 돌린다. 앱 시계는 손으로 옮긴다 — 값의 나이(미리 갱신 판단)는
 * 앱 시계로 재고, Redis 의 만료는 실제 시각이라 시계를 옮겨도 값은 Redis 에 남아 있다(TTL 30초, 미리 갱신은 읽은 지 24초부터).
 */
@DisplayName("Redis 읽기 캐시 — 미리 갱신")
class ReadCacheRefreshTest {

    private static final GenericContainer<?> REDIS = new GenericContainer<>("valkey/valkey:9").withExposedPorts(6379);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    static LettuceConnectionFactory connections;
    static StringRedisTemplate redis;

    ReadCacheTest.MutableClock clock;
    Tasks tasks;
    Events events;
    ReadCache cache;
    ReadCache.Spec<String> spec;
    AtomicInteger loads;

    @BeforeAll
    static void start() {
        REDIS.start();
        RedisStandaloneConfiguration server = new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379));
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
        clock = new ReadCacheTest.MutableClock(Instant.now().truncatedTo(ChronoUnit.MILLIS));
        tasks = new Tasks();
        events = new Events();
        loads = new AtomicInteger();
        cache = cache(tasks);
        spec = cache.spec("s", String.class, Duration.ofSeconds(30));
    }

    @Test
    @DisplayName("읽은 지 TTL 의 80% 전에는 갱신하지 않는다 — 그 뒤 요청은 들고 있던 값을 바로 받고 갱신을 하나 맡긴다. 갱신이 넣은 값이 다음 요청에 간다")
    void refreshesOnlyAfterEightyPercent() {
        Instant start = clock.instant();
        cache.entry(spec, "g", "k", () -> load("v1"));
        clock.advance(Duration.ofMillis(23_999));
        assertThat(cache.entry(spec, "g", "k", () -> load("v2")).value()).isEqualTo("v1");
        assertThat(tasks.pending()).as("24초 전").isZero();

        clock.advance(Duration.ofMillis(1));
        assertThat(cache.entry(spec, "g", "k", () -> load("v2")).value()).as("기다리지 않고 들고 있던 값").isEqualTo("v1");
        assertThat(tasks.pending()).isEqualTo(1);
        assertThat(loads.get()).isEqualTo(1);

        tasks.runAll();
        ReadCache.Entry<String> refreshed = cache.entry(spec, "g", "k", () -> load("v3"));
        assertThat(refreshed.value()).isEqualTo("v2");
        assertThat(refreshed.loadedAt()).as("갱신을 시작한 시각").isEqualTo(start.plusSeconds(24));
        assertThat(events.outcomes).as("요청마다 하나").containsExactly(Outcome.LOAD, Outcome.HIT, Outcome.HIT_REFRESH, Outcome.HIT);
        assertThat(events.refreshes).as("갱신 작업마다 하나").containsExactly(RefreshResult.STORED);
    }

    @Test
    @DisplayName("같은 키의 갱신은 이 인스턴스에서 하나만 맡긴다")
    void oneRefreshPerKey() {
        cache.entry(spec, "g", "k", () -> load("v1"));
        clock.advance(Duration.ofSeconds(24));
        for (int i = 0; i < 5; i++) {
            cache.entry(spec, "g", "k", () -> load("v2"));
        }
        assertThat(tasks.pending()).isEqualTo(1);
        tasks.runAll();
        assertThat(loads.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("갱신이 실패해도 Redis 의 만료는 늘지 않는다 — 값과 남은 시간이 그대로다")
    void failedRefreshDoesNotExtend() {
        cache.entry(spec, "g", "k", () -> load("v1"));
        String key = onlyKey();
        long before = redis.getExpire(key, TimeUnit.MILLISECONDS);
        clock.advance(Duration.ofSeconds(24));
        cache.entry(spec, "g", "k", () -> {
            loads.incrementAndGet();
            throw new IllegalStateException("DB");
        });
        tasks.runAll();

        assertThat(cache.entry(spec, "g", "k", () -> load("v2")).value()).isEqualTo("v1");
        assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).as("만료가 늘지 않았다").isLessThanOrEqualTo(before);
        assertThat(events.refreshes).containsExactly(RefreshResult.FAILED);
    }

    @Test
    @DisplayName("세대가 오른 뒤 끝난 갱신의 값은 새 세대 조회에 보이지 않는다 — 갱신은 맡길 때의 세대 키에 넣는다")
    void refreshStartedBeforeBumpIsNotServed() {
        cache.entry(spec, "g", "k", () -> load("옛"));
        clock.advance(Duration.ofSeconds(24));
        cache.entry(spec, "g", "k", () -> load("갱신(옛 세대)"));
        cache.bumpAfterCommit("g");
        tasks.runAll();
        assertThat(cache.entry(spec, "g", "k", () -> load("새")).value()).isEqualTo("새");
    }

    @Test
    @DisplayName("갱신이 도는 중에 값이 사라지면 그 뒤 요청은 갱신을 기다린다 — loader 는 갱신의 한 번뿐")
    void missWhileRefreshingWaitsForIt() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ReadCache threaded = cache(command -> new Thread(command).start());
        ReadCache.Spec<String> s = threaded.spec("s", String.class, Duration.ofSeconds(30));
        threaded.entry(s, "g", "k", () -> load("v1"));
        clock.advance(Duration.ofSeconds(24));
        threaded.entry(s, "g", "k", () -> {
            started.countDown();
            await(release);
            return load("v2");
        });
        assertThat(started.await(5, TimeUnit.SECONDS)).as("갱신이 시작됐다").isTrue();
        redis.delete(onlyKey());   // 만료(또는 밀려남) — 갱신은 아직 도는 중
        CompletableFuture<String> waiting = CompletableFuture.supplyAsync(() -> threaded.entry(s, "g", "k", () -> load("직접")).value());
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!events.outcomes.contains(Outcome.WAIT) && System.nanoTime() < until) {
            Thread.sleep(5);
        }
        release.countDown();

        assertThat(waiting.get(5, TimeUnit.SECONDS)).isEqualTo("v2");
        assertThat(loads.get()).as("처음 읽기 + 갱신 — 기다린 요청은 읽지 않았다").isEqualTo(2);
        assertThat(events.outcomes).contains(Outcome.WAIT);
    }

    @Test
    @DisplayName("실행기가 차면 맡기지 않는다 — 요청은 들고 있던 값을 받고, 그 값이 만료될 때까지 그 키는 다시 맡기지 않는다(거절을 요청마다 세지 않는다)")
    void rejectedRefreshIsSkipped() {
        boolean[] reject = {true};
        AtomicInteger attempts = new AtomicInteger();
        ReadCache rejecting = cache(command -> {
            attempts.incrementAndGet();
            if (reject[0]) {
                throw new RejectedExecutionException("full");
            }
            tasks.execute(command);
        });
        ReadCache.Spec<String> s = rejecting.spec("s", String.class, Duration.ofSeconds(30));
        rejecting.entry(s, "g", "k", () -> load("v1"));
        clock.advance(Duration.ofSeconds(27));   // 80%(24초)보다 늦게 — 차단이 거절 시각이 아니라 값의 만료(30초)까지인지 가른다
        for (int i = 0; i < 3; i++) {
            assertThat(rejecting.entry(s, "g", "k", () -> load("v2")).value()).isEqualTo("v1");
        }
        assertThat(events.refreshes).as("거절은 한 번만 센다").containsExactly(RefreshResult.REJECTED);
        assertThat(attempts.get()).as("실행기에는 한 번만 맡기려 했다").isEqualTo(1);
        assertThat(events.outcomes.getLast()).as("맡기지 못한 요청은 바로 받음으로 센다").isEqualTo(Outcome.HIT);

        reject[0] = false;
        clock.advance(Duration.ofMillis(2999));   // 만료 직전 — 차단은 그 값이 만료될 때까지다
        assertThat(rejecting.entry(s, "g", "k", () -> load("v2")).value()).as("아직 들고 있던 값").isEqualTo("v1");
        assertThat(tasks.pending()).as("실행기가 비어도 그 값이 만료되기 전에는 다시 맡기지 않는다").isZero();
        redis.delete(onlyKey());   // 만료
        clock.advance(Duration.ofMillis(1));
        assertThat(rejecting.entry(s, "g", "k", () -> load("v2")).value()).as("만료 뒤 직접 읽기").isEqualTo("v2");
        clock.advance(Duration.ofSeconds(24));
        rejecting.entry(s, "g", "k", () -> load("v3"));
        assertThat(tasks.pending()).as("새 값의 주기에는 다시 맡긴다(차단이 풀렸다)").isEqualTo(1);
    }

    @Test
    @DisplayName("실행기가 거절 아닌 예외로 맡기지 못해도 그 값이 만료될 때까지 다시 맡기지 않는다")
    void failedSubmitIsNotRepeated() {
        AtomicInteger attempts = new AtomicInteger();
        ReadCache broken = cache(command -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("broken");
        });
        ReadCache.Spec<String> s = broken.spec("s", String.class, Duration.ofSeconds(30));
        broken.entry(s, "g", "k", () -> load("v1"));
        clock.advance(Duration.ofSeconds(24));
        for (int i = 0; i < 3; i++) {
            assertThat(broken.entry(s, "g", "k", () -> load("v2")).value()).isEqualTo("v1");
        }
        assertThat(attempts.get()).as("실행기에는 한 번만 맡기려 했다").isEqualTo(1);
        assertThat(events.refreshes).containsExactly(RefreshResult.REJECTED);
    }

    @Test
    @DisplayName("갱신 실행기가 없으면(공개 기본 생성자) 미리 갱신하지 않는다")
    void noRefresherNoRefresh() {
        ReadCache plain = new ReadCache(redis, JSON, "svc", CircuitBreakerConfig.ofDefaults(), true, clock, Duration.ofSeconds(5));
        ReadCache.Spec<String> s = plain.spec("s", String.class, Duration.ofSeconds(30));
        plain.entry(s, "g", "k", () -> load("v1"));
        clock.advance(Duration.ofSeconds(24));
        assertThat(plain.entry(s, "g", "k", () -> load("v2")).value()).isEqualTo("v1");
        assertThat(loads.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("갱신이 성공하면 다음 주기에 다시 맡긴다. 실패하면 그 값이 만료될 때까지 맡기지 않고, 만료 뒤 직접 읽은 새 값의 주기에는 다시 맡긴다")
    void refreshSlotIsReleasedAfterEachRun() {
        cache.entry(spec, "g", "k", () -> load("v1"));
        clock.advance(Duration.ofSeconds(24));
        cache.entry(spec, "g", "k", () -> load("v2"));
        tasks.runAll();   // 성공 — v2(읽은 시각 24초)
        clock.advance(Duration.ofSeconds(27));   // 51초 — 80%(48초)보다 늦게 실패시켜, 차단이 값의 만료(54초)까지인지 가른다
        cache.entry(spec, "g", "k", () -> {
            throw new IllegalStateException("DB");
        });
        assertThat(tasks.pending()).as("성공 뒤 다음 주기").isEqualTo(1);
        tasks.runAll();   // 실패
        cache.entry(spec, "g", "k", () -> load("v3"));
        assertThat(tasks.pending()).as("실패 뒤 — 그 값이 만료될 때까지 다시 맡기지 않는다").isZero();
        redis.delete(onlyKey());   // 만료
        clock.advance(Duration.ofSeconds(3));
        assertThat(cache.entry(spec, "g", "k", () -> load("v4")).value()).as("직접 읽는다").isEqualTo("v4");
        clock.advance(Duration.ofSeconds(24));
        cache.entry(spec, "g", "k", () -> load("v5"));
        assertThat(tasks.pending()).as("새 값의 주기에는 다시 맡긴다(차단이 풀렸다)").isEqualTo(1);
    }

    @Test
    @DisplayName("넣지 못한 갱신(넣지 않는 값)은 들고 있던 값이 만료될 때까지 다시 맡기지 않는다")
    void notStoredRefreshIsNotRepeated() {
        cache.entry(spec, "g", "k", () -> load("v1"), value -> !value.isEmpty());
        clock.advance(Duration.ofSeconds(24));
        cache.entry(spec, "g", "k", () -> load(""), value -> !value.isEmpty());
        tasks.runAll();
        assertThat(events.refreshes).containsExactly(RefreshResult.NOT_STORED);
        for (int i = 0; i < 3; i++) {
            assertThat(cache.entry(spec, "g", "k", () -> load(""), value -> !value.isEmpty()).value()).isEqualTo("v1");
        }
        assertThat(tasks.pending()).as("만료 전에는 다시 맡기지 않는다").isZero();
        redis.delete(onlyKey());   // 만료
        clock.advance(Duration.ofSeconds(6));
        assertThat(cache.entry(spec, "g", "k", () -> load("v2"), value -> !value.isEmpty()).value()).isEqualTo("v2");
        clock.advance(Duration.ofSeconds(24));
        cache.entry(spec, "g", "k", () -> load("v3"), value -> !value.isEmpty());
        assertThat(tasks.pending()).as("새 값의 주기에는 다시 맡긴다(차단이 풀렸다)").isEqualTo(1);
    }

    @Test
    @DisplayName("큐에서 기다리는 사이 세대가 올랐으면 갱신은 읽지 않는다 — 옛 세대 키는 아무도 읽지 않는다")
    void lateRefreshSkipsAfterBump() {
        cache.entry(spec, "g", "k", () -> load("v1"));
        clock.advance(Duration.ofSeconds(24));
        cache.entry(spec, "g", "k", () -> load("갱신"));
        cache.bumpAfterCommit("g");   // 이 인스턴스는 오른 세대로 아직 조회하지 않았다 — 갱신이 Redis 의 지금 세대를 본다
        tasks.runAll();
        assertThat(events.refreshes).containsExactly(RefreshResult.SKIPPED_NEWER);
        assertThat(loads.get()).as("처음 읽기뿐 — 갱신은 읽지 않았다").isEqualTo(1);
        cache.entry(spec, "g", "k", () -> load("새 세대"));
        clock.advance(Duration.ofSeconds(24));
        cache.entry(spec, "g", "k", () -> load("새 세대 갱신"));
        assertThat(tasks.pending()).as("건너뛴 뒤 자리가 풀려 다음 주기에 다시 맡긴다").isEqualTo(1);
    }

    @Test
    @DisplayName("Redis 가 비워져 세대가 0 으로 돌아가도 미리 갱신은 계속 넣는다 — 이 인스턴스가 전에 본 더 큰 번호로 건너뛰지 않는다")
    void refreshKeepsWorkingAfterGenerationReset() {
        cache.bumpAfterCommit("g");
        cache.entry(spec, "g", "k", () -> load("v1"));   // 이 인스턴스가 세대 1 을 본다
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();   // Redis 재시작 · 교체 · 사람이 지움 — 세대 키가 없어 0
        cache.entry(spec, "g", "k", () -> load("v2"));
        clock.advance(Duration.ofSeconds(24));
        cache.entry(spec, "g", "k", () -> load("v3"));
        tasks.runAll();
        assertThat(events.refreshes).containsExactly(RefreshResult.STORED);
        assertThat(cache.entry(spec, "g", "k", () -> load("v4")).value()).isEqualTo("v3");
    }

    @Test
    @DisplayName("큐에서 늦게 돈 갱신은 그사이 더 새 값이 Redis 에 들어왔으면 읽지 않는다")
    void lateRefreshSkipsWhenNewerValueExists() {
        cache.entry(spec, "g", "k", () -> load("v1"));
        clock.advance(Duration.ofSeconds(24));
        cache.entry(spec, "g", "k", () -> load("갱신"));
        redis.delete(onlyKey());   // 만료(또는 밀려남)
        clock.advance(Duration.ofSeconds(1));
        assertThat(cache.entry(spec, "g", "k", () -> load("직접")).value()).as("요청이 직접 읽었다").isEqualTo("직접");
        tasks.runAll();
        assertThat(events.refreshes).containsExactly(RefreshResult.SKIPPED_NEWER);
        assertThat(loads.get()).as("갱신은 읽지 않았다").isEqualTo(2);
        assertThat(cache.entry(spec, "g", "k", () -> load("다른 값")).value()).isEqualTo("직접");
    }

    private ReadCache cache(Executor refresher) {
        return new ReadCache(redis, JSON, "svc", CircuitBreakerConfig.ofDefaults(), true, clock, Duration.ofSeconds(5), refresher, events);
    }

    private String load(String value) {
        loads.incrementAndGet();
        return value;
    }

    private static String onlyKey() {
        Set<String> keys = redis.keys("svc:cache:s:*");
        assertThat(keys).hasSize(1);
        return keys.iterator().next();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 맡긴 일을 모아 두는 실행기. */
    static final class Tasks implements Executor {
        private final List<Runnable> queued = new ArrayList<>();

        @Override
        public synchronized void execute(Runnable command) {
            queued.add(command);
        }

        synchronized int pending() {
            return queued.size();
        }

        void runAll() {
            List<Runnable> now;
            synchronized (this) {
                now = new ArrayList<>(queued);
                queued.clear();
            }
            now.forEach(Runnable::run);
        }
    }

    static final class Events implements ReadCacheEvents {
        final List<Outcome> outcomes = Collections.synchronizedList(new ArrayList<>());
        final List<RefreshResult> refreshes = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void refreshed(String cache, RefreshResult result) {
            refreshes.add(result);
        }

        @Override
        public void served(String cache, Outcome outcome) {
            outcomes.add(outcome);
        }

        @Override
        public void waited(String cache, Duration waited) {
        }
    }
}
