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
        clock.advance(Duration.ofSeconds(2));
        assertThat(cache.get(body, "products", id, () -> new Item("db")).name()).as("읽은 지 10분 1초 — 믿는 시간 안이어도 TTL 이 지났다").isEqualTo("db");
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
    @DisplayName("이 인스턴스가 세대를 올리기 전에 읽기 시작한 값은 옛 번호로 남아도 사본으로 쓰지 않는다")
    void loadStartedBeforeOwnBumpIsNotServed() {
        get(cache, held, "warm");   // 세대 0 을 본다
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
    @DisplayName("믿는 시간이 0 이하면 만들 때 거절한다")
    void heldForMustBePositive() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> cache.spec("x", Item.class, TTL, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 캐시마다의 요청 결과. */
    static final class Outcomes implements ReadCacheEvents {
        private final List<String> served = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void served(String cache, Outcome outcome) {
            served.add(cache + "|" + outcome.name());
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
