package com.grandis.nova.common.redis.cache;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisSystemException;
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

import static org.assertj.core.api.Assertions.assertThat;

/** Redis 를 못 읽을 때 이 인스턴스의 사본으로 답하는 캐시(ReadCache.OutageCopies). */
class ReadCacheOutageCopyTest {

    private static final GenericContainer<?> REDIS = new GenericContainer<>("valkey/valkey:9").withExposedPorts(6379);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration TTL = Duration.ofMinutes(10);
    private static final Duration HELD_FOR = Duration.ofMinutes(1);
    private static final int REFRESH_POINT = ReadCache.REFRESH_AFTER_PERCENT;

    static LettuceConnectionFactory connections;
    static StringRedisTemplate redis;

    ReadCacheTest.MutableClock clock;
    Outcomes outcomes;
    ReadCache cache;
    ReadCache.Spec<Item> held;
    ReadCache.Spec<Item> plain;
    List<ReadCache.LoadPath> loads;

    record Item(String name) {
    }

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
        redis.opsForValue().set("svc:cache:gen:products", "0");
        clock = new ReadCacheTest.MutableClock(Instant.now().truncatedTo(ChronoUnit.MILLIS));
        outcomes = new Outcomes();
        cache = instance(redis);
        held = cache.spec("held", Item.class, TTL, HELD_FOR);
        plain = cache.spec("plain", Item.class, TTL);
        loads = Collections.synchronizedList(new ArrayList<>());
    }

    private ReadCache instance(StringRedisTemplate template) {
        return new ReadCache(template, JSON, "svc", CircuitBreakerConfig.ofDefaults(), true, clock, ReadCache.WAIT_FOR_LOAD, null, outcomes);
    }

    private String get(ReadCache from, ReadCache.Spec<Item> spec, String name) {
        return from.get(spec, "products", "1", path -> {
            loads.add(path);
            return new Item(name);
        }).name();
    }

    @Test
    @DisplayName("차단기가 열리면 사본을 켠 캐시는 원천을 부르지 않고 사본으로 답한다 — 읽은 시각도 그대로, 켜지 않은 캐시는 우회")
    void openBreakerServesTheCopy() {
        Instant filledAt = clock.instant();
        get(cache, held, "v1");
        get(cache, plain, "p1");
        loads.clear();
        clock.advance(Duration.ofSeconds(5));

        cache.breaker().transitionToForcedOpenState();
        ReadCache.Entry<Item> served = cache.entry(held, "products", "1", () -> new Item("db"));
        String bypassed = get(cache, plain, "p2");
        cache.breaker().transitionToClosedState();

        assertThat(served.value().name()).isEqualTo("v1");
        assertThat(served.loadedAt()).as("max-age 가 사본의 나이만큼 줄게 읽은 시각을 그대로 준다").isEqualTo(filledAt);
        assertThat(bypassed).isEqualTo("p2");
        assertThat(loads).as("원천은 켜지 않은 캐시만").containsExactly(ReadCache.LoadPath.BYPASS);
        assertThat(outcomes.of("held")).endsWith(ReadCacheEvents.Outcome.HELD);
    }

    @Test
    @DisplayName("세대를 못 읽으면 Redis 에서 세대를 마지막으로 읽은 뒤 믿는 시간까지만 사본 — 사본을 읽은 시각이 아니라 세대를 확인한 시각부터 잰다")
    void copyIsTrustedOnlyForHeldForSinceTheGenerationWasLastRead() {
        get(cache, held, "v1");
        clock.advance(Duration.ofSeconds(30));
        get(cache, held, "unused");   // 히트 — 세대를 다시 읽었다(사본은 처음 값 그대로)
        cache.breaker().transitionToForcedOpenState();
        loads.clear();

        clock.advance(Duration.ofSeconds(59));
        assertThat(get(cache, held, "db")).as("확인 뒤 59초 — 사본(처음 읽은 지는 89초)").isEqualTo("v1");
        clock.advance(Duration.ofSeconds(2));
        assertThat(get(cache, held, "db")).as("확인 뒤 61초 — 다른 인스턴스의 올리기를 모르는 상한을 넘었다").isEqualTo("db");
        cache.breaker().transitionToClosedState();
        assertThat(loads).containsExactly(ReadCache.LoadPath.BYPASS);
    }

    @Test
    @DisplayName("옛 번호를 읽은 조회가 늦게 끝나도 새 번호의 확인 시각을 늘리지 않는다 — 믿는 시간은 새 번호를 읽은 때부터")
    void lateReadOfAnOlderGenerationDoesNotExtendTheTrust() throws Exception {
        PausingRedisTemplate template = new PausingRedisTemplate(connections, key -> key.equals("svc:cache:gen:products"));
        ReadCache paused = instance(template);
        ReadCache.Spec<Item> spec = paused.spec("held", Item.class, TTL, HELD_FOR);
        Thread late = new Thread(() -> {
            template.pause.set(true);   // 세대 0 을 읽은 직후 멈춘다
            paused.get(spec, "products", "old", () -> new Item("old"));
        });
        late.start();
        assertThat(template.read.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        instance(redis).bumpNow("products");   // 다른 인스턴스가 올렸다 — 세대 1
        paused.get(spec, "products", "1", () -> new Item("v1"));   // 세대 1 을 읽고 사본을 남겼다(확인 시각 지금)
        clock.advance(Duration.ofSeconds(50));
        template.resume.countDown();   // 세대 0 을 읽은 조회가 이제 끝난다
        late.join(10_000);
        paused.breaker().transitionToForcedOpenState();
        clock.advance(Duration.ofSeconds(15));   // 세대 1 을 읽은 지 65초, 늦은 조회가 끝난 지 15초
        loads.clear();

        String served = paused.get(spec, "products", "1", path -> {
            loads.add(path);
            return new Item("db");
        }).name();
        paused.breaker().transitionToClosedState();

        assertThat(served).as("믿는 시간(1분)이 지났다").isEqualTo("db");
    }

    @Test
    @DisplayName("사본도 값의 TTL 을 넘겨 쓰지 않는다 — 평소 Redis 에 있었을 값보다 오래된 값을 내지 않는다")
    void copyNeverOutlivesTheTtl() {
        ReadCache.Spec<Item> body = cache.spec("body", Item.class, TTL, TTL);
        String id = "b";
        cache.get(body, "products", id, () -> new Item("v1"));
        clock.advance(Duration.ofMinutes(9));
        cache.get(body, "products", id, () -> new Item("unused"));   // 세대 확인
        cache.breaker().transitionToForcedOpenState();

        clock.advance(Duration.ofSeconds(59));
        assertThat(cache.get(body, "products", id, () -> new Item("db")).name()).as("읽은 지 9분 59초").isEqualTo("v1");
        clock.advance(Duration.ofSeconds(1));
        assertThat(cache.get(body, "products", id, () -> new Item("db")).name()).as("읽은 지 정확히 10분 — 믿는 시간 안이어도 TTL 이 다 됐다").isEqualTo("db");
        cache.breaker().transitionToClosedState();
    }

    @Test
    @DisplayName("세대는 읽었는데 값만 못 읽으면 그 번호의 사본 — 다른 인스턴스가 세대를 올렸으면 옛 번호의 사본은 쓰지 않는다")
    void valueReadFailureUsesTheCopyOfTheCurrentGeneration() {
        PausingRedisTemplate template = new PausingRedisTemplate(connections, key -> key.startsWith("svc:cache:held:"));
        ReadCache failing = instance(template);
        ReadCache.Spec<Item> spec = failing.spec("held", Item.class, TTL, HELD_FOR);
        get(failing, spec, "v1");
        loads.clear();
        template.failWith = new RedisSystemException("값 읽기 실패", null);

        template.fail.set(true);
        assertThat(get(failing, spec, "db")).isEqualTo("v1");
        assertThat(loads).isEmpty();

        instance(redis).bumpNow("products");   // 다른 인스턴스가 올렸다
        template.failAt = 2;   // 이 스레드가 지켜본 두 번째 값 GET
        template.fail.set(true);
        assertThat(get(failing, spec, "db")).as("세대는 새 번호로 읽었다 — 그 번호의 사본은 없다").isEqualTo("db");
        assertThat(loads).containsExactly(ReadCache.LoadPath.BYPASS);
    }

    @Test
    @DisplayName("장애 중 이 인스턴스가 올리려 한 세대(미뤄짐)의 사본은 쓰지 않는다 — 이 인스턴스가 고친 것은 바로 빠진다")
    void ownBumpDuringOutageDropsTheCopy() {
        get(cache, held, "v1");
        cache.breaker().transitionToForcedOpenState();
        clock.advance(Duration.ofSeconds(1));
        loads.clear();

        cache.bumpAfterCommit("products");   // 차단기가 열려 미룬다 — 번호는 그대로
        String served = get(cache, held, "db");
        cache.breaker().transitionToClosedState();

        assertThat(served).isEqualTo("db");
        assertThat(loads).containsExactly(ReadCache.LoadPath.BYPASS);
    }

    @Test
    @DisplayName("올리기와 같은 시각에 읽기 시작한 사본도 쓰지 않는다 — 그 값이 고치기 전인지 뒤인지 가를 수 없다")
    void copyReadAtTheBumpInstantIsNotServed() {
        get(cache, held, "v1");
        cache.breaker().transitionToForcedOpenState();
        loads.clear();

        cache.bumpAfterCommit("products");   // 시계를 움직이지 않았다 — 사본의 읽은 시각과 같다
        String served = get(cache, held, "db");
        cache.breaker().transitionToClosedState();

        assertThat(served).isEqualTo("db");
    }

    @Test
    @DisplayName("이 인스턴스가 세대를 올리기 전에 읽기 시작한 값은 옛 번호로 남아도 사본으로 쓰지 않는다")
    void loadStartedBeforeOwnBumpIsNotServed() {
        get(cache, held, "warm");   // 세대 0 을 본다
        clock.advance(TTL.multipliedBy(REFRESH_POINT).dividedBy(100));   // 사본이 미리 갱신 시점을 지나야 다음 조회가 Redis 로 간다
        redis.delete(redis.keys("svc:cache:held:*"));
        cache.get(held, "products", "1", () -> {
            clock.advance(Duration.ofSeconds(1));
            cache.bumpAfterCommit("products");   // 읽는 사이 이 인스턴스가 고쳤다(트랜잭션 밖이라 바로 올린다)
            return new Item("before-bump");
        });
        cache.breaker().transitionToForcedOpenState();
        loads.clear();

        String served = get(cache, held, "db");
        cache.breaker().transitionToClosedState();

        assertThat(served).as("세대 0 사본(올리기 전에 읽음)을 내지 않는다").isEqualTo("db");
        assertThat(loads).containsExactly(ReadCache.LoadPath.BYPASS);
    }

    /** Redis 에 든 그 키의 값을 같은 세대 그대로 바꾼다 — 조회가 Redis 값을 읽었는지 사본으로 답했는지 가른다. */
    private void overwriteInRedis(ReadCache.Spec<Item> spec, String name) {
        java.util.Set<String> keys = redis.keys("svc:cache:" + spec.name() + ":*");
        assertThat(keys).hasSize(1);
        redis.opsForValue().set(keys.iterator().next(), "{\"loadedAt\":" + clock.millis() + ",\"value\":{\"name\":\"" + name + "\"}}");
    }

    @Test
    @DisplayName("평소 적중도 같은 세대의 사본이 미리 갱신 시점 전이면 Redis 값을 읽지 않는다 — 읽은 시각 그대로, 켜지 않은 캐시는 Redis 값")
    void freshCopyAnswersWithoutTheValueRead() {
        Instant filledAt = clock.instant();
        get(cache, held, "v1");
        get(cache, plain, "p1");
        overwriteInRedis(held, "in-redis");
        overwriteInRedis(plain, "p-in-redis");
        loads.clear();
        clock.advance(TTL.multipliedBy(REFRESH_POINT - 1).dividedBy(100));

        ReadCache.Entry<Item> served = cache.entry(held, "products", "1", () -> new Item("db"));

        assertThat(served.value().name()).isEqualTo("v1");
        assertThat(served.loadedAt()).as("TTL 은 처음 읽은 시각부터 — 사본을 꺼낸 때부터 다시 세지 않는다").isEqualTo(filledAt);
        assertThat(outcomes.of("held")).endsWith(ReadCacheEvents.Outcome.LOCAL);
        assertThat(get(cache, plain, "unused")).isEqualTo("p-in-redis");
        assertThat(loads).isEmpty();
    }

    @Test
    @DisplayName("미리 갱신 시점이 지나면 Redis 로 간다 — 그 뒤엔 Redis 에서 읽은 값이 새 사본이다")
    void copyPastRefreshPointGoesToRedis() {
        get(cache, held, "v1");
        loads.clear();
        clock.advance(TTL.multipliedBy(REFRESH_POINT).dividedBy(100));
        overwriteInRedis(held, "in-redis");

        assertThat(get(cache, held, "unused")).isEqualTo("in-redis");
        assertThat(outcomes.of("held")).endsWith(ReadCacheEvents.Outcome.HIT);
        assertThat(get(cache, held, "unused")).as("Redis 에서 읽은 값(읽은 시각이 지금)이 사본이 됐다").isEqualTo("in-redis");
        assertThat(outcomes.of("held")).endsWith(ReadCacheEvents.Outcome.LOCAL);
        assertThat(loads).isEmpty();
    }

    @Test
    @DisplayName("다른 인스턴스가 세대를 올리면 사본은 쓰지 않는다 — 세대는 늘 Redis 에서 읽는다")
    void otherInstanceBumpSkipsTheCopy() {
        get(cache, held, "v1");
        loads.clear();

        instance(redis).bumpNow("products");

        assertThat(get(cache, held, "v2")).isEqualTo("v2");
        assertThat(loads).containsExactly(ReadCache.LoadPath.FILL);
    }

    @Test
    @DisplayName("다른 인스턴스가 넣은 값을 Redis 에서 읽기만 해도 사본이 남는다")
    void hitKeepsACopy() {
        ReadCache other = instance(redis);
        other.get(other.spec("held", Item.class, TTL, HELD_FOR), "products", "1", () -> new Item("from-other"));
        assertThat(get(cache, held, "unused")).isEqualTo("from-other");
        loads.clear();

        cache.breaker().transitionToForcedOpenState();
        String served = get(cache, held, "db");
        cache.breaker().transitionToClosedState();

        assertThat(served).isEqualTo("from-other");
        assertThat(loads).isEmpty();
    }

    @Test
    @DisplayName("값이 비어 자리를 잡은 뒤 다시 보기가 Redis 실패면 사본으로 답한다 — 원천을 부르지 않는다")
    void recheckFailureServesTheCopy() {
        PausingRedisTemplate template = new PausingRedisTemplate(connections, key -> key.startsWith("svc:cache:held:"));
        ReadCache failing = instance(template);
        ReadCache.Spec<Item> spec = failing.spec("held", Item.class, TTL, HELD_FOR);
        get(failing, spec, "v1");
        redis.delete(redis.keys("svc:cache:held:*"));   // Redis 에서 밀려났다 — 사본은 남아 있다
        loads.clear();
        template.failWith = new RedisSystemException("다시 보기 실패", null);
        template.failAt = 2;   // 첫 GET 은 없음, 자리를 잡은 뒤의 GET 이 실패한다

        template.fail.set(true);
        String served = get(failing, spec, "db");

        assertThat(served).isEqualTo("v1");
        assertThat(loads).isEmpty();
    }

    @Test
    @DisplayName("넣지 않는 값은 사본도 남기지 않는다")
    void unstorableValueLeavesNoCopy() {
        cache.entry(held, "products", "1", () -> new Item("partial"), item -> false);
        cache.breaker().transitionToForcedOpenState();

        String served = get(cache, held, "db");
        cache.breaker().transitionToClosedState();

        assertThat(served).isEqualTo("db");
    }

    @Test
    @DisplayName("세대를 못 읽어도 다른 인스턴스가 올린 번호를 이 인스턴스가 이미 봤으면(다른 캐시 · 다른 키로) 옛 번호의 사본은 쓰지 않는다")
    void bumpSeenThroughAnotherCacheRetiresTheCopy() {
        get(cache, held, "v1");   // 세대 0 의 사본
        instance(redis).bumpNow("products");   // 다른 인스턴스가 올렸다 — 세대 1
        cache.get(plain, "products", "2", () -> new Item("other"));   // 사본을 켜지 않은 다른 캐시 · 다른 키로 세대 1 을 봤다
        cache.breaker().transitionToForcedOpenState();
        loads.clear();

        String served = get(cache, held, "db");
        cache.breaker().transitionToClosedState();

        assertThat(served).as("세대 0 사본을 내지 않는다").isEqualTo("db");
        assertThat(loads).containsExactly(ReadCache.LoadPath.BYPASS);
    }

    @Test
    @DisplayName("다시 보기가 Redis 실패면 그 채우기 자리에 붙어 기다린 요청도 같은 사본을 받는다 — 원천은 부르지 않는다")
    void waiterOfAFailedRecheckGetsTheCopy() throws Exception {
        PausingRedisTemplate template = new PausingRedisTemplate(connections, key -> key.startsWith("svc:cache:held:"));
        ReadCache failing = instance(template);
        ReadCache.Spec<Item> spec = failing.spec("held", Item.class, TTL, HELD_FOR);
        get(failing, spec, "v1");
        clock.advance(TTL.multipliedBy(REFRESH_POINT).dividedBy(100));   // 사본이 미리 갱신 시점을 지나야 다음 조회가 Redis 로 간다
        redis.delete(redis.keys("svc:cache:held:*"));
        loads.clear();
        template.failWith = new RedisSystemException("다시 보기 실패", null);
        template.failAt = 2;
        template.pauseAt = 2;   // 자리를 잡은 뒤의 GET 에서 멈췄다가 던진다
        java.util.concurrent.atomic.AtomicReference<Object> owner = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Object> waiter = new java.util.concurrent.atomic.AtomicReference<>();
        Thread holder = new Thread(() -> {
            template.pause.set(true);
            template.fail.set(true);
            try {
                owner.set(failing.entry(spec, "products", "1", () -> { loads.add(ReadCache.LoadPath.FILL); return new Item("db"); }));
            } catch (Throwable e) {
                owner.set(e);
            }
        });
        holder.start();
        assertThat(template.read.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        Thread joiner = new Thread(() -> {
            try {
                waiter.set(failing.entry(spec, "products", "1", () -> { loads.add(ReadCache.LoadPath.FILL); return new Item("db"); }));
            } catch (Throwable e) {
                waiter.set(e);
            }
        });
        joiner.start();
        assertThat(outcomes.waiting.await(10, java.util.concurrent.TimeUnit.SECONDS)).as("같이 기다리는 쪽이 자리에 붙었다").isTrue();
        template.resume.countDown();
        holder.join(10_000);
        joiner.join(10_000);

        assertThat(owner.get()).isInstanceOf(ReadCache.Entry.class);
        assertThat(((ReadCache.Entry<?>) owner.get()).value()).isEqualTo(new Item("v1"));
        assertThat(waiter.get()).as("자리 주인의 사본을 그대로 받는다").isSameAs(owner.get());
        assertThat(loads).isEmpty();
    }

    @Test
    @DisplayName("저장 상한에 걸려 Redis 에 넣지 못한 값도 사본은 남는다 — 이 세대의 지금 값이다")
    void valueOverTheStoreBudgetIsStillCopied() {
        // Redis 몫은 키 · 고정 비용 · 값의 1/4 까지 세어(값 하나 약 200) 하나만 들어가고, 사본은 JSON 글자 수(약 45)라 둘 다 남는다
        ReadCache.Spec<Item> tight = cache.spec("tight", JSON.constructType(Item.class), TTL, 300, HELD_FOR);
        cache.get(tight, "products", "1", () -> new Item("v1"));
        cache.get(tight, "products", "2", () -> new Item("v2"));
        assertThat(redis.keys("svc:cache:tight:*")).as("두 번째는 저장 상한에 걸려 Redis 에 없다").hasSize(1);
        cache.breaker().transitionToForcedOpenState();

        String served = cache.get(tight, "products", "2", () -> new Item("db")).name();
        cache.breaker().transitionToClosedState();

        assertThat(served).isEqualTo("v2");
    }

    @Test
    @DisplayName("사본의 양은 JSON 글자 수 상한으로 묶인다 — 넘친 사본은 밀려나 원천으로 간다")
    void copiesAreBoundedByTheirJsonLength() {
        ReadCache.Spec<Item> small = cache.spec("small", JSON.constructType(Item.class), TTL, 300, HELD_FOR);   // 값 하나 약 45자 — 스무 개는 못 든다
        for (int i = 0; i < 20; i++) {
            String id = String.valueOf(i);
            cache.get(small, "products", id, () -> new Item("v" + id));
        }
        small.outage().cleanUp();
        cache.breaker().transitionToForcedOpenState();
        int held = 0;
        for (int i = 0; i < 20; i++) {
            String id = String.valueOf(i);
            if (cache.get(small, "products", id, () -> new Item("db")).name().startsWith("v")) {
                held++;
            }
        }
        cache.breaker().transitionToClosedState();

        assertThat(held).as("상한 안의 사본만 남는다").isBetween(1, 19);
    }

    @Test
    @DisplayName("믿는 시간이 0 이하면 만들 때 거절한다")
    void heldForMustBePositive() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> cache.spec("x", Item.class, TTL, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 캐시마다의 요청 결과. */
    static final class Outcomes implements ReadCacheEvents {
        private final List<String> served = Collections.synchronizedList(new ArrayList<>());
        final java.util.concurrent.CountDownLatch waiting = new java.util.concurrent.CountDownLatch(1);

        @Override
        public void served(String cache, Outcome outcome) {
            served.add(cache + "|" + outcome.name());
            if (outcome == Outcome.WAIT) {
                waiting.countDown();
            }
        }

        @Override
        public void waited(String cache, Duration waited) {
        }

        @Override
        public void refreshed(String cache, RefreshResult result) {
        }

        List<Outcome> of(String cache) {
            synchronized (served) {
                return served.stream().filter(s -> s.startsWith(cache + "|")).map(s -> Outcome.valueOf(s.substring(cache.length() + 1))).toList();
            }
        }
    }
}
