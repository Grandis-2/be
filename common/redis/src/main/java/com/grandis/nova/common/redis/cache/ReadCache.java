package com.grandis.nova.common.redis.cache;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * 공개 조회 결과를 Redis 에 잠깐 들고 있다가 같은 요청에 돌려주는 읽기 캐시. 정본은 늘 DB 다 — 캐시는 DB 를 덜 읽으려고 둔다.
 *
 * <ul>
 *   <li><b>장애</b>: Redis 읽기 · 쓰기가 실패하면 캐시 없이 loader(DB)로 답한다. 실패가 이어지면 차단기가 열려 {@link #OPEN_FOR} 동안
 *       Redis 를 부르지 않는다 — 요청마다 Redis 제한 시간만큼 기다리지 않게. 그 뒤 몇 번 불러 보고 되면 다시 쓴다.</li>
 *   <li><b>몰림</b>: 같은 키(세대 번호 포함)를 동시에 찾는 요청은 이 인스턴스 안에서 loader 를 한 번만 부르고 나머지는 그 결과를 받는다 —
 *       인기 키가 비는 순간(만료 · Redis 복구 직후) DB 로 한꺼번에 가지 않게. loader 의 예외(404 등)도 같이 받는다. 같이 받는 쪽은
 *       {@link #WAIT_FOR_LOAD} 까지만 기다린다(넘으면 503).</li>
 *   <li><b>무효화</b>: 키에 세대 번호를 넣고, 바뀐 쪽이 {@link #bumpAfterCommit} 으로 번호를 올린다 — 조건마다 다른 키(목록 · 검색)를 하나씩
 *       찾아 지우지 않는다. 올리기는 트랜잭션 커밋 뒤라 롤백된 수정으로 비우지 않는다. 올리기가 실패하면 ERROR 이고 그 값은 TTL 이 지나야
 *       바뀐다. 커밋 전에 DB 를 읽은 요청이 올린 뒤에 옛 값을 쓰는 경우도 TTL 이 끝낸다 — TTL 이 최악의 지연이다.</li>
 *   <li><b>배포</b>: 값은 JSON 이고 키에 그 타입 모양의 지문({@link Shape})을 넣는다. 모양이 다른 버전(blue/green 이 겹치는 동안)은 서로의 키를
 *       읽지 않는다 — 읽으면 새 칸이 null 로 조용히 비어 나간다. 지문이 같은데 못 읽는 값은 없는 것으로 보고 다시 채운다.</li>
 * </ul>
 * 값은 바뀌지 않는(불변) 객체여야 한다 — 같이 기다린 요청들이 한 객체를 나눠 쓴다. 지문은 레코드 칸 · enum 만 본다 — 값 타입에 Jackson 이름
 * 애너테이션(@JsonProperty 등)이나 레코드 아닌 클래스를 쓰면 캐시 이름에 버전을 붙여 바꾼다.
 * <p>운영 Redis 는 여러 서비스가 나눠 쓰는 단일 노드이고 메모리가 차면 TTL 있는 키부터 지운다(volatile-lru) — 토큰 폐기 표식도 TTL 이 있어
 * 같이 밀려날 수 있다. 그래서 캐시 키가 끝없이 늘 수 있는 조회(자유 검색어 · 깊은 페이지)는 쓰는 쪽이 캐시하지 않는다. 세대 키는 TTL 이 없어
 * 이 정책이 지우지 않는다(사람이 지우면 0 으로 돌아간다).
 */
public final class ReadCache {

    private static final Logger log = LoggerFactory.getLogger(ReadCache.class);

    /** 차단기가 열린 뒤 Redis 를 부르지 않는 시간. */
    static final Duration OPEN_FOR = Duration.ofSeconds(10);
    /** 같은 키의 진행 중 조회를 같이 받는 쪽이 기다리는 상한. */
    static final Duration WAIT_FOR_LOAD = Duration.ofSeconds(5);
    /** 캐시 하나가 인스턴스 하나에서 Redis 에 동시에 들고 있는 양의 기본 상한({@link StoreBudget}). */
    static final long DEFAULT_BUDGET_BYTES = 4L * 1024 * 1024;
    /** Redis 에 넣는 모양({@link Stored})의 버전 — 키에 들어간다. */
    static final String FORMAT = "f1";

    private final StringRedisTemplate redis;
    private final JsonMapper json;
    private final String prefix;
    private final CircuitBreaker breaker;
    private final ConcurrentHashMap<String, CompletableFuture<Object>> loading = new ConcurrentHashMap<>();
    private final Clock clock;
    private final Duration waitForLoad;
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
        this(redis, json, service, breakerConfig, true, Clock.systemUTC(), WAIT_FOR_LOAD);
    }

    ReadCache(StringRedisTemplate redis, JsonMapper json, String service, CircuitBreakerConfig breakerConfig, boolean enabled, Clock clock,
              Duration waitForLoad) {
        this.enabled = enabled;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.waitForLoad = Objects.requireNonNull(waitForLoad, "waitForLoad");
        this.redis = enabled ? Objects.requireNonNull(redis, "redis") : redis;
        this.json = Objects.requireNonNull(json, "json");
        this.prefix = Objects.requireNonNull(service, "service") + ":cache:";
        this.breaker = CircuitBreaker.of(service + "-read-cache", breakerConfig);
    }

    /**
     * 끈 캐시 — 늘 loader 로 답하고 Redis 를 부르지 않는다. 시험이 DB 를 JDBC 로 직접 고치는 경우처럼 세대를 올리지 않는 쓰기를 쓰는 곳에서 쓴다.
     */
    public static ReadCache off(JsonMapper json, String service) {
        return new ReadCache(null, json, service, CircuitBreakerConfig.ofDefaults(), false, Clock.systemUTC(), WAIT_FOR_LOAD);
    }

    /** 캐시 하나의 이름 · 값 타입 · TTL. 이름과 타입 지문이 키에 들어간다. 메모리 예산은 {@link #DEFAULT_BUDGET_BYTES}. */
    public <T> Spec<T> spec(String name, Class<T> type, Duration ttl) {
        return spec(name, json.constructType(type), ttl, DEFAULT_BUDGET_BYTES);
    }

    public <T> Spec<T> spec(String name, tools.jackson.core.type.TypeReference<T> type, Duration ttl) {
        return spec(name, json.constructType(type), ttl, DEFAULT_BUDGET_BYTES);
    }

    /** @param budgetBytes 이 인스턴스가 이 캐시로 Redis 에 동시에 들고 있는 양의 상한({@link StoreBudget}) */
    public <T> Spec<T> spec(String name, Class<T> type, Duration ttl, long budgetBytes) {
        return spec(name, json.constructType(type), ttl, budgetBytes);
    }

    public <T> Spec<T> spec(String name, tools.jackson.core.type.TypeReference<T> type, Duration ttl, long budgetBytes) {
        return spec(name, json.constructType(type), ttl, budgetBytes);
    }

    private <T> Spec<T> spec(String name, JavaType type, Duration ttl, long budgetBytes) {
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("ttl must be positive: " + ttl);
        }
        return new Spec<>(name, type, Shape.fingerprint(type), ttl, new StoreBudget(budgetBytes, clock));
    }

    /**
     * 캐시하지 않는 조회 — loader 로 답하고 시작 시각을 읽은 시각으로 준다(캐시를 거친 값과 같은 기준으로 max-age 를 정하게).
     * 키가 끝없이 늘 수 있는 조회(자유 검색어 · 깊은 페이지)처럼 쓰는 쪽이 넣지 않기로 한 조회에 쓴다.
     */
    public <T> Entry<T> uncached(Supplier<T> loader) {
        Instant start = clock.instant();
        return new Entry<>(loader.get(), start);
    }

    /** {@link #entry} 의 값만. */
    public <T> T get(Spec<T> spec, String generation, String id, Supplier<T> loader) {
        return entry(spec, generation, id, loader).value();
    }

    /** null 이 아닌 값은 넣는다. */
    public <T> Entry<T> entry(Spec<T> spec, String generation, String id, Supplier<T> loader) {
        return entry(spec, generation, id, loader, value -> true);
    }

    /**
     * 세대 {@code generation} 의 키 {@code id} 값과 그 값을 DB 에서 읽은 시각. 없으면 loader 로 만들어 넣는다. loader 가 null 을 주면 넣지 않는다.
     * 세대 번호를 못 읽으면(Redis 장애) 캐시를 건너뛰고 loader 로 답한다 — 아무 번호나 쓰면 옛 값을 줄 수 있다.
     * 읽은 시각은 응답의 HTTP 캐시 시간({@link Entry#remaining})을 정하는 데 쓴다 — 앞단 캐시가 이 값을 TTL 넘게 들고 있지 않게.
     */
    public <T> Entry<T> entry(Spec<T> spec, String generation, String id, Supplier<T> loader, Predicate<T> storable) {
        if (!enabled) {
            return uncached(loader);
        }
        Long current = call(() -> currentGeneration(generation));
        if (current == null) {
            // 세대를 모르면 장애 중인 요청끼리만 같이 받는다 — 정상 세대의 진행 중 조회와 섞이지 않게
            return load("down|" + spec.name() + ":" + generation + ":" + id, null, spec, loader, storable);
        }
        String key = prefix + spec.name() + ":" + FORMAT + "." + spec.fingerprint() + ":" + generation + "." + current + ":" + id;
        String cached = call(() -> redis.opsForValue().get(key));
        if (cached != null) {
            try {
                Stored<T> stored = json.readValue(cached, json.getTypeFactory().constructParametricType(Stored.class, spec.type()));
                return new Entry<>(stored.value(), Instant.ofEpochMilli(stored.loadedAt()));
            } catch (JacksonException e) {
                log.warn("읽기 캐시 값을 읽지 못해 다시 채운다 key={}", key, e);
            }
        }
        // 완성된 키(세대 번호 포함)로 줄 세운다 — 세대가 오른 뒤 온 요청이 옛 세대의 진행 중 조회를 같이 받지 않게
        return load(key, key, spec, loader, storable);
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
     * 같은 자리(slot)의 loader 를 이 인스턴스에서 한 번만 부르고, 나머지는 그 결과(또는 예외)를 같이 받는다. key 가 null 이면 넣지 않는다.
     * 같이 받는 쪽은 {@link #WAIT_FOR_LOAD} 까지만 기다리고 넘으면 503(다시 시도) — 멈춘 조회 하나에 같은 상품 요청이 전부 묶이지 않게.
     * 넘었다고 각자 DB 로 가게 풀지 않는다(DB 가 느린 때 부하가 그만큼 는다). 진행 중인 조회는 그대로 두고 끝나면 자리가 빈다.
     */
    @SuppressWarnings("unchecked")
    private <T> Entry<T> load(String slot, String key, Spec<T> spec, Supplier<T> loader, Predicate<T> storable) {
        CompletableFuture<Object> mine = new CompletableFuture<>();
        CompletableFuture<Object> running = loading.putIfAbsent(slot, mine);
        if (running != null) {
            try {
                return (Entry<T>) running.get(waitForLoad.toMillis(), TimeUnit.MILLISECONDS);
            } catch (ExecutionException e) {
                throw e.getCause() instanceof RuntimeException runtime ? runtime : new IllegalStateException(e.getCause());
            } catch (TimeoutException e) {
                log.warn("읽기 캐시 — 같은 키의 조회가 {} 안에 끝나지 않아 503 slot={}", waitForLoad, slot);
                throw new BusinessException(CommonErrorCode.DEPENDENCY_UNAVAILABLE);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BusinessException(CommonErrorCode.DEPENDENCY_UNAVAILABLE);
            }
        }
        try {
            Instant loadedAt = clock.instant();   // 읽기 전 시각 — 응답의 나이를 짧게 잡지 않게
            Entry<T> entry = new Entry<>(loader.get(), loadedAt);
            mine.complete(entry);
            if (key != null && entry.value() != null && storable.test(entry.value())) {
                store(key, spec, entry);
            }
            return entry;
        } catch (RuntimeException | Error e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            loading.remove(slot, mine);
        }
    }

    private <T> void store(String key, Spec<T> spec, Entry<T> entry) {
        String body;
        try {
            body = json.writeValueAsString(new Stored<>(entry.loadedAt().toEpochMilli(), entry.value()));
        } catch (JacksonException e) {
            log.warn("읽기 캐시에 넣을 값을 JSON 으로 못 바꿨다 key={}", key, e);
            return;
        }
        // 남은 시간만큼만 둔다 — 읽는 데 걸린 시간도 TTL 에서 뺀다
        Duration left = spec.ttl().minus(Duration.between(entry.loadedAt(), clock.instant()));
        if (left.toMillis() <= 0) {
            return;
        }
        // 이 인스턴스가 이 캐시로 들고 있는 양이 상한을 넘으면 넣지 않는다(DB 로 답한다) — 나눠 쓰는 Redis 의 다른 키(토큰 폐기 표식 · 대기열)를 덜 밀어내게
        long charge = StoreBudget.charge(key.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        if (!spec.budget().tryTake(charge, left)) {
            spec.budget().warnSkipped(spec.name());
            return;
        }
        call(() -> {
            redis.opsForValue().set(key, body, left);
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
     * 값과 DB 에서 읽은 시각.
     *
     * @param loadedAt 이 값을 만든 loader 가 시작한 시각
     */
    public record Entry<T>(T value, Instant loadedAt) {

        /** 값만 바꾼 사본 — 읽은 시각은 그대로(응답 봉투로 감쌀 때). */
        public <R> Entry<R> map(java.util.function.Function<T, R> mapper) {
            return new Entry<>(mapper.apply(value), loadedAt);
        }

        /** 이 값을 TTL 안에서 더 들고 있어도 되는 시간 — 앞단 HTTP 캐시의 max-age 상한. 0 보다 작으면 0. */
        public Duration remaining(Duration ttl, Instant now) {
            Duration left = ttl.minus(Duration.between(loadedAt, now));
            return left.isNegative() ? Duration.ZERO : left;
        }
    }

    /** Redis 에 넣는 모양. 이 모양을 바꾸면 {@link #FORMAT} 을 올린다. */
    record Stored<T>(long loadedAt, T value) {
    }

    /**
     * @param fingerprint 값 타입 모양의 지문 — 레코드 칸 이름 · 타입, enum 상수가 바뀌면 달라진다
     * @param budget      이 캐시가 이 인스턴스에서 Redis 에 동시에 들고 있는 양의 상한
     */
    public record Spec<T>(String name, JavaType type, String fingerprint, Duration ttl, StoreBudget budget) {
    }

    /**
     * 캐시 하나가 이 인스턴스에서 Redis 에 넣어 둔 값 중 아직 살아 있는(만료 전) 것의 합 상한. 넣을 때 만료 시각과 차지할 양을 적어 두고, 만료된 것을
     * 빼고 더해 상한을 넘으면 넣지 않는다(DB 로 답한다) — 입력 조합(필터 · 페이지)이 아무리 많아도 이 인스턴스가 동시에 들고 있는 양이 상한에 묶인다.
     * <ul>
     *   <li>차지할 양은 {@link #charge} — Valkey 9.1.2 에서 MEMORY USAGE 로 잰 값(키 · 값 외에 작은 값은 40~60B, 60KB 는 약 9%, 200KB 는 약 15%)보다
     *       크게 잡는다. 큰 값의 몫은 할당기(jemalloc)의 크기 구간이 두 배마다 4칸이라 최대 약 25% 다.</li>
     *   <li>TTL 전에 지워지거나 밀려난 값은 만료 시각까지 그대로 센다(보수적). 같은 키를 다시 넣으면 둘 다 센다(보수적).</li>
     *   <li><b>인스턴스 하나의 상한이다.</b> 재시작하면 0 에서 다시 센다 — 그때 Redis 에 남은 옛 값은 세지 않는다. 그래서 서비스 전체 점유는
     *       상한 × (떠 있는 인스턴스 + TTL 안에 내려간 인스턴스) 로 잡는다(배포 중 겹침 포함).</li>
     * </ul>
     */
    public static final class StoreBudget {

        /** 키 하나에 더해 셀 고정 비용 — 잰 값(41~59B)보다 크게. */
        static final int PER_KEY_OVERHEAD = 64;

        private final long capacity;
        private final Clock clock;
        private final java.util.PriorityQueue<long[]> live = new java.util.PriorityQueue<>(java.util.Comparator.comparingLong(e -> e[0]));
        private long used;
        private long lastWarn;

        StoreBudget(long capacity, Clock clock) {
            if (capacity <= 0) {
                throw new IllegalArgumentException("budget must be positive: " + capacity);
            }
            this.capacity = capacity;
            this.clock = clock;
        }

        /** Redis 메모리로 셀 양 — 키 + 값 + 고정 비용 + 값의 1/4(큰 값의 할당 구간 몫). */
        static long charge(int keyBytes, int valueBytes) {
            return keyBytes + valueBytes + PER_KEY_OVERHEAD + valueBytes / 4L;
        }

        /** ttl 동안 살아 있을 bytes 를 넣어도 상한 안인가. 그렇다면 센다. */
        synchronized boolean tryTake(long bytes, Duration ttl) {
            long now = clock.millis();
            while (!live.isEmpty() && live.peek()[0] <= now) {
                used -= live.poll()[1];
            }
            if (used + bytes > capacity) {
                return false;
            }
            live.add(new long[] {now + ttl.toMillis(), bytes});
            used += bytes;
            return true;
        }

        synchronized long used() {
            return used;
        }

        /** 상한을 넘어 넣지 않았다 — 분마다 한 번만 남긴다. */
        synchronized void warnSkipped(String name) {
            long now = clock.millis();
            if (now - lastWarn >= 60_000) {
                lastWarn = now;
                log.warn("읽기 캐시 저장 상한({} bytes)을 넘어 넣지 않았다 — DB 로 답한다 cache={}", capacity, name);
            }
        }
    }
}
