package com.grandis.nova.common.redis.cache;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 공개 조회 결과를 Redis 에 잠깐 들고 있다가 같은 요청에 돌려주는 읽기 캐시. 정본은 늘 DB 다 — 캐시는 DB 를 덜 읽으려고 둔다.
 *
 * <ul>
 *   <li><b>장애</b>: Redis 읽기 · 쓰기가 실패하면 캐시 없이 loader(DB)로 답한다. 실패가 이어지면 차단기가 열려 {@link #OPEN_FOR} 동안
 *       Redis 를 부르지 않는다 — 요청마다 Redis 제한 시간만큼 기다리지 않게. 그 뒤 몇 번 불러 보고 되면 다시 쓴다.</li>
 *   <li><b>몰림</b>: 같은 키를 동시에 찾는 요청은 이 인스턴스 안에서 loader 를 한 번만 부르고 나머지는 그 결과를 받는다 — 인기 키가 비는 순간
 *       (만료 · Redis 복구 직후) DB 로 한꺼번에 가지 않게. loader 의 예외(404 등)도 같이 받는다.</li>
 *   <li><b>무효화</b>: 키에 세대 번호를 넣고, 바뀐 쪽이 {@link #bumpAfterCommit} 으로 번호를 올린다 — 조건마다 다른 키(목록 · 검색)를 하나씩
 *       찾아 지우지 않는다. 올리기는 트랜잭션 커밋 뒤라 롤백된 수정으로 비우지 않는다. 올리기가 실패하면 ERROR 이고 그 값은 TTL 이 지나야
 *       바뀐다. 커밋 전에 DB 를 읽은 요청이 올린 뒤에 옛 값을 쓰는 경우도 TTL 이 끝낸다 — TTL 이 최악의 지연이다.</li>
 *   <li><b>배포</b>: 값은 JSON 이고 키에 그 타입 모양의 지문({@link Shape})을 넣는다. 모양이 다른 버전(blue/green 이 겹치는 동안)은 서로의 키를
 *       읽지 않는다 — 읽으면 새 칸이 null 로 조용히 비어 나간다. 지문이 같은데 못 읽는 값은 없는 것으로 보고 다시 채운다.</li>
 * </ul>
 * 값은 바뀌지 않는(불변) 객체여야 한다 — 같이 기다린 요청들이 한 객체를 나눠 쓴다.
 */
public final class ReadCache {

    private static final Logger log = LoggerFactory.getLogger(ReadCache.class);

    /** 차단기가 열린 뒤 Redis 를 부르지 않는 시간. */
    static final Duration OPEN_FOR = Duration.ofSeconds(10);

    private final StringRedisTemplate redis;
    private final JsonMapper json;
    private final String prefix;
    private final CircuitBreaker breaker;
    private final ConcurrentHashMap<String, CompletableFuture<Object>> loading = new ConcurrentHashMap<>();
    /** false 면 Redis 를 부르지 않고 늘 loader 로 답한다({@link #off}). */
    private final boolean enabled;

    /** @param service 키 앞에 붙는 서비스 이름(catalog · order) — 같은 Redis 를 나눠 쓰는 서비스끼리 키가 겹치지 않게 */
    public ReadCache(StringRedisTemplate redis, JsonMapper json, String service) {
        this(redis, json, service, CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(20)
                .minimumNumberOfCalls(5)
                .failureRateThreshold(50)
                .waitDurationInOpenState(OPEN_FOR)
                .permittedNumberOfCallsInHalfOpenState(3)
                .automaticTransitionFromOpenToHalfOpenEnabled(false)
                .build());
    }

    ReadCache(StringRedisTemplate redis, JsonMapper json, String service, CircuitBreakerConfig breakerConfig) {
        this(redis, json, service, breakerConfig, true);
    }

    private ReadCache(StringRedisTemplate redis, JsonMapper json, String service, CircuitBreakerConfig breakerConfig, boolean enabled) {
        this.enabled = enabled;
        this.redis = enabled ? Objects.requireNonNull(redis, "redis") : redis;
        this.json = Objects.requireNonNull(json, "json");
        this.prefix = Objects.requireNonNull(service, "service") + ":cache:";
        this.breaker = CircuitBreaker.of(service + "-read-cache", breakerConfig);
    }

    /**
     * 끈 캐시 — 늘 loader 로 답하고 Redis 를 부르지 않는다. 시험이 DB 를 JDBC 로 직접 고치는 경우처럼 세대를 올리지 않는 쓰기를 쓰는 곳에서 쓴다.
     */
    public static ReadCache off(JsonMapper json, String service) {
        return new ReadCache(null, json, service, CircuitBreakerConfig.ofDefaults(), false);
    }

    /** 캐시 하나의 이름 · 값 타입 · TTL. 이름과 타입 지문이 키에 들어간다. */
    public <T> Spec<T> spec(String name, Class<T> type, Duration ttl) {
        return spec(name, json.constructType(type), ttl);
    }

    public <T> Spec<T> spec(String name, tools.jackson.core.type.TypeReference<T> type, Duration ttl) {
        return spec(name, json.constructType(type), ttl);
    }

    private <T> Spec<T> spec(String name, JavaType type, Duration ttl) {
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("ttl must be positive: " + ttl);
        }
        return new Spec<>(name, type, Shape.fingerprint(type), ttl);
    }

    /**
     * 세대 {@code generation} 의 키 {@code id} 값을 돌려준다. 없으면 loader 로 만들어 넣는다. loader 가 null 을 주면 넣지 않는다.
     * 세대 번호를 못 읽으면(Redis 장애) 캐시를 건너뛰고 loader 로 답한다 — 아무 번호나 쓰면 옛 값을 줄 수 있다.
     */
    public <T> T get(Spec<T> spec, String generation, String id, Supplier<T> loader) {
        if (!enabled) {
            return loader.get();
        }
        String slot = spec.name() + ":" + generation + ":" + id;
        Long current = call(() -> currentGeneration(generation));
        if (current == null) {
            return load(slot, null, spec, loader);
        }
        String key = prefix + spec.name() + ":" + spec.fingerprint() + ":" + generation + "." + current + ":" + id;
        String cached = call(() -> redis.opsForValue().get(key));
        if (cached != null) {
            try {
                return json.readValue(cached, spec.type());
            } catch (JacksonException e) {
                log.warn("읽기 캐시 값을 읽지 못해 다시 채운다 key={}", key, e);
            }
        }
        return load(slot, key, spec, loader);
    }

    /**
     * 세대 번호를 올린다 — 그 세대를 키에 넣은 값이 전부 다음 조회부터 새로 채워진다. 트랜잭션 안이면 커밋 뒤에(한 트랜잭션에서 같은 세대를
     * 여러 번 불러도 한 번), 밖이면 바로 한다. 실패해도 던지지 않는다(수정은 이미 커밋됐다) — ERROR 를 남기고 TTL 이 끝낸다.
     */
    public void bumpAfterCommit(String generation) {
        if (!enabled) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            bump(generation);
            return;
        }
        @SuppressWarnings("unchecked")
        Set<String> pending = (Set<String>) TransactionSynchronizationManager.getResource(this);
        if (pending == null) {
            Set<String> created = new LinkedHashSet<>();
            TransactionSynchronizationManager.bindResource(this, created);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    created.forEach(ReadCache.this::bump);
                }

                @Override
                public void afterCompletion(int status) {
                    TransactionSynchronizationManager.unbindResourceIfPossible(ReadCache.this);
                }
            });
            pending = created;
        }
        pending.add(generation);
    }

    private void bump(String generation) {
        if (call(() -> redis.opsForValue().increment(prefix + "gen:" + generation)) == null) {
            log.error("읽기 캐시 세대를 올리지 못했다 — TTL 이 지날 때까지 옛 값이 보일 수 있다 generation={}", generation);
        }
    }

    CircuitBreaker.State breakerState() {
        return breaker.getState();
    }

    private long currentGeneration(String generation) {
        String value = redis.opsForValue().get(prefix + "gen:" + generation);
        return value == null ? 0 : Long.parseLong(value);
    }

    /**
     * 같은 자리(캐시 이름 + id)의 loader 를 한 번만 부른다. 세대와 상관없이 자리로 줄 세운다 — Redis 장애로 세대를 못 읽는 동안에도 같은 요청이
     * DB 로 몰리지 않게. key 가 null 이면(세대를 못 읽음) 넣지 않는다.
     */
    @SuppressWarnings("unchecked")
    private <T> T load(String slot, String key, Spec<T> spec, Supplier<T> loader) {
        CompletableFuture<Object> mine = new CompletableFuture<>();
        CompletableFuture<Object> running = loading.putIfAbsent(slot, mine);
        if (running != null) {
            try {
                return (T) running.join();
            } catch (CompletionException e) {
                throw e.getCause() instanceof RuntimeException runtime ? runtime : e;
            }
        }
        try {
            T value = loader.get();
            mine.complete(value);
            if (key != null && value != null) {
                store(key, spec, value);
            }
            return value;
        } catch (RuntimeException | Error e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            loading.remove(slot, mine);
        }
    }

    private <T> void store(String key, Spec<T> spec, T value) {
        String body;
        try {
            body = json.writeValueAsString(value);
        } catch (JacksonException e) {
            log.warn("읽기 캐시에 넣을 값을 JSON 으로 못 바꿨다 key={}", key, e);
            return;
        }
        call(() -> {
            redis.opsForValue().set(key, body, spec.ttl());
            return Boolean.TRUE;
        });
    }

    /** Redis 호출 하나. 실패 · 차단기 열림이면 null — 호출하는 쪽이 캐시 없이 간다. */
    private <R> R call(Supplier<R> operation) {
        try {
            return breaker.executeSupplier(operation);
        } catch (CallNotPermittedException e) {
            return null;
        } catch (RuntimeException e) {
            log.warn("읽기 캐시 Redis 호출 실패 — 캐시 없이 간다: {}", e.toString());
            return null;
        }
    }

    /**
     * @param fingerprint 값 타입 모양의 지문 — 레코드 칸 이름 · 타입, enum 상수가 바뀌면 달라진다
     */
    public record Spec<T>(String name, JavaType type, String fingerprint, Duration ttl) {
    }
}
