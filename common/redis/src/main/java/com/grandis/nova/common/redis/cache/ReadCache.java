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
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;
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
 *       찾아 지우지 않는다. 올리기는 트랜잭션 커밋 뒤라 롤백된 수정으로 비우지 않는다. 올리기는 차단기를 거치지 않고, 실패하면 미뤄 두었다가 Redis 를
 *       다시 읽을 수 있게 된 첫 조회에서 올린다. 커밋 전에 DB 를 읽은 요청의 옛 값은 그때 정한 옛 세대 키에 들어가 새 세대 조회에는 안 보인다.</li>
 *   <li><b>시계</b>: 값의 만료는 앱 벽시계로 정한 절대 시각(PXAT)이다 — 앱 시계가 Redis 보다 빠르면 그만큼 더 살고, 느리면 덜 산다. 그래서 신선도의
 *       최악은 TTL + 앱 · Redis 시계 차다(둘 다 AWS 시간 동기화를 쓴다는 가정).</li>
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
    /** 앱과 Redis 의 시계 차 여유 — 저장 몫을 값의 절대 만료보다 이만큼 더 잡는다({@link StoreBudget}). */
    static final Duration CLOCK_SKEW_MARGIN = Duration.ofSeconds(2);
    /** 캐시 하나가 인스턴스 하나에서 Redis 에 동시에 들고 있는 양의 기본 상한({@link StoreBudget}). */
    static final long DEFAULT_BUDGET_BYTES = 4L * 1024 * 1024;
    /** 세대 올리기를 바로 다시 해 보는 횟수 — 넘으면 미뤄 둔다. */
    static final int BUMP_ATTEMPTS = 2;
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
    /** 올리지 못한 세대 — Redis 를 다시 읽을 수 있게 되면 그때 올린다({@link #retryPendingBumps}). */
    private final Set<String> pendingBumps = ConcurrentHashMap.newKeySet();
    /** 세대마다 이 인스턴스가 마지막으로 본 번호 — 오른 것을 처음 보면 옛 세대로 넣은 키를 지우고 저장 몫을 돌려준다. */
    private final ConcurrentHashMap<String, Long> seenGenerations = new ConcurrentHashMap<>();

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
        return new Spec<>(name, type, Shape.fingerprint(type), ttl, new StoreBudget(budgetBytes, System::nanoTime));
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
            return load("down|" + spec.name() + ":" + generation + ":" + id, null, spec, loader, storable, generation, -1);
        }
        if (!pendingBumps.isEmpty() && retryPendingBumps(generation)) {
            current = call(() -> currentGeneration(generation));   // 방금 올린 세대 — 새 번호로 읽는다
            if (current == null) {
                return load("down|" + spec.name() + ":" + generation + ":" + id, null, spec, loader, storable, generation, -1);
            }
        }
        releaseOlderGenerations(spec, generation, current);
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
        return load(key, key, spec, loader, storable, generation, current);
    }

    /**
     * 세대 번호를 올린다 — 그 세대를 키에 넣은 값이 전부 다음 조회부터 새로 채워진다. 트랜잭션 안이면 그 트랜잭션의 커밋 뒤에(한 트랜잭션에서 같은
     * 세대를 여러 번 불러도 한 번), 밖이면 바로 한다. 안쪽 새 트랜잭션(REQUIRES_NEW)은 자기 커밋에 붙는다 — 지금 트랜잭션의 동기화 목록에서 찾는다.
     * 실패해도 던지지 않는다(수정은 이미 커밋됐다) — {@link #bump} 가 다시 시도하고 끝내 못 올리면 나중에 다시 한다.
     */
    public void bumpAfterCommit(String generation) {
        if (!enabled) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            bump(generation);
            return;
        }
        for (TransactionSynchronization registered : TransactionSynchronizationManager.getSynchronizations()) {
            if (registered instanceof BumpOnCommit mine && mine.owner == this) {
                mine.generations.add(generation);
                return;
            }
        }
        BumpOnCommit created = new BumpOnCommit(this);
        created.generations.add(generation);
        TransactionSynchronizationManager.registerSynchronization(created);
    }

    /** 한 트랜잭션의 커밋 뒤에 그 트랜잭션에서 부른 세대를 한 번씩 올린다. */
    private static final class BumpOnCommit implements TransactionSynchronization {

        private final ReadCache owner;
        private final Set<String> generations = new LinkedHashSet<>();

        private BumpOnCommit(ReadCache owner) {
            this.owner = owner;
        }

        @Override
        public void afterCommit() {
            generations.forEach(owner::bump);
        }
    }

    /**
     * 세대를 올린다. 차단기를 거치지 않는다 — 차단기는 인스턴스마다 따로라, 이 인스턴스만 열려 있어도 올리기를 버리면 다른 인스턴스가 옛 값을
     * TTL 동안 낸다. 두 번 해 보고 안 되면 {@link #pendingBumps} 에 두고 Redis 를 다시 읽을 수 있게 된 첫 조회에서 올린다(ERROR 로그).
     * 시간 초과는 결과를 모르는 실패다 — 서버가 늦게 실행해 한 번 더 오를 수 있다(실측: 멈춘 Redis 를 풀자 시간 초과된 두 번이 다 들어갔다).
     * 더 오르는 것은 값을 한 번 더 채울 뿐이라 그대로 둔다.
     */
    private void bump(String generation) {
        RuntimeException last = null;
        for (int attempt = 0; attempt < BUMP_ATTEMPTS; attempt++) {
            try {
                redis.opsForValue().increment(prefix + "gen:" + generation);
                pendingBumps.remove(generation);
                return;
            } catch (RuntimeException e) {
                last = e;
            }
        }
        pendingBumps.add(generation);
        log.error("읽기 캐시 세대를 올리지 못해 나중에 다시 올린다 — 그때까지 옛 값이 보일 수 있다 generation={}", generation, last);
    }

    /** 미룬 세대를 올린다(Redis 를 방금 읽은 조회에서). generation 을 이번에 올렸으면 true. */
    private boolean retryPendingBumps(String generation) {
        boolean bumpedThis = false;
        for (String pending : List.copyOf(pendingBumps)) {
            try {
                redis.opsForValue().increment(prefix + "gen:" + pending);
                pendingBumps.remove(pending);
                bumpedThis |= pending.equals(generation);
            } catch (RuntimeException e) {
                return bumpedThis;   // 아직 안 된다 — 다음 조회에서
            }
        }
        return bumpedThis;
    }

    /**
     * 세대가 오른 것을 처음 보면 이 인스턴스가 옛 세대로 넣은 이 캐시의 키를 지우고, 지운 몫을 저장 상한에서 돌려준다 — 안 그러면 옛 값의 몫이 TTL 동안
     * 남아 예산이 찬 인스턴스가 그동안 새로 넣지 못한다. 지우지 못하면(Redis 실패) 몫도 남겨 둔다(값이 Redis 에 남아 있을 수 있다).
     */
    private void releaseOlderGenerations(Spec<?> spec, String generation, long current) {
        Long seen = seenGenerations.put(spec.name() + "|" + generation, current);
        if (seen == null || seen >= current) {
            return;
        }
        List<StoreBudget.Hold> older = spec.budget().olderThan(generation, current);
        if (older.isEmpty()) {
            return;
        }
        List<String> keys = older.stream().map(StoreBudget.Hold::key).toList();
        if (call(() -> redis.unlink(keys)) != null) {
            older.forEach(spec.budget()::release);
        }
    }

    CircuitBreaker.State breakerState() {
        return breaker.getState();
    }

    /** 시험용 — 차단기를 직접 연다. */
    CircuitBreaker breaker() {
        return breaker;
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
    private <T> Entry<T> load(String slot, String key, Spec<T> spec, Supplier<T> loader, Predicate<T> storable, String generation,
                              long generationNumber) {
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
                store(key, spec, entry, generation, generationNumber);
            }
            return entry;
        } catch (RuntimeException | Error e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            loading.remove(slot, mine);
        }
    }

    private <T> void store(String key, Spec<T> spec, Entry<T> entry, String generation, long generationNumber) {
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
        // 만료는 절대 시각(PXAT)으로 준다 — 서버가 SET 을 늦게 처리해도 이 시각에 사라진다(늦게 처리된 만큼 더 살지 않는다).
        // 몫은 지금부터 같은 시간 + 시계 차 여유만큼 잡는다 — 결과를 모르는 실패(시간 초과)에서도 따로 셀 것이 없다
        long expiresAtEpochMillis = clock.millis() + left.toMillis();
        StoreBudget.Hold hold = spec.budget().reserve(charge, left.plus(CLOCK_SKEW_MARGIN), key, generation, generationNumber);
        if (hold == null) {
            spec.budget().warnSkipped(spec.name());
            return;
        }
        byte[] keyBytes = key.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] bodyBytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try {
            breaker.executeRunnable(() -> redis.execute((org.springframework.data.redis.core.RedisCallback<Boolean>) connection ->
                    connection.stringCommands().set(keyBytes, bodyBytes,
                            org.springframework.data.redis.core.types.Expiration.unixTimestamp(expiresAtEpochMillis, TimeUnit.MILLISECONDS),
                            org.springframework.data.redis.connection.RedisStringCommands.SetOption.upsert())));
        } catch (CallNotPermittedException e) {
            spec.budget().release(hold);   // 차단기가 막아 보내지 않았다
        } catch (RuntimeException e) {
            log.warn("읽기 캐시 Redis 호출 실패 — 캐시 없이 간다: {}", e.toString());   // 결과 모름 — 몫은 잡은 그대로(절대 만료라 그 안에 사라진다)
        }
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
     *   <li>값은 절대 만료 시각(PXAT = 앱 벽시계 지금 + 남은 TTL)으로 넣고, 몫은 넣기 전에 같은 시간 + {@link #CLOCK_SKEW_MARGIN} 만큼 잡는다 — 서버가
     *       SET 을 늦게 처리해도 값은 그 시각에 사라지므로, 결과를 모르는 실패(시간 초과)에서도 몫이 값보다 먼저 풀리지 않는다. 앱과 Redis 의 시계 차가
     *       여유보다 크면 이 보장이 깨진다(둘 다 AWS 시간 동기화를 쓴다는 가정 — 운영에서 재지는 않았다). 차단기가 막아 보내지 않았을 때만 바로 돌려준다.</li>
     *   <li>경과 시간은 단조 증가 시간원(System.nanoTime)으로 잰다 — 벽시계를 앞으로 맞춰도 살아 있는 값의 몫이 풀리지 않는다. 응답 신선도(loadedAt)는
     *       벽시계로 따로 잰다.</li>
     *   <li>TTL 전에 지워지거나 밀려난 값은 만료 시각까지 그대로 센다(보수적). 같은 키를 다시 넣으면 둘 다 센다(보수적).</li>
     *   <li><b>인스턴스 하나의 상한이다.</b> 재시작하면 0 에서 다시 센다 — 그때 Redis 에 남은 옛 값은 세지 않는다. 그래서 서비스 전체 점유는
     *       상한 × (떠 있는 인스턴스 + TTL 안에 내려간 인스턴스) 로 잡는다(배포 중 겹침 포함).</li>
     * </ul>
     */
    public static final class StoreBudget {

        /** 키 하나에 더해 셀 고정 비용 — 잰 값(41~59B)보다 크게. */
        static final int PER_KEY_OVERHEAD = 64;

        private final long capacity;
        private final LongSupplier nanos;
        private final java.util.PriorityQueue<Hold> live = new java.util.PriorityQueue<>((a, b) -> Long.compare(a.expiresAtNanos - b.expiresAtNanos, 0));
        private long used;
        private long lastWarnNanos = Long.MIN_VALUE;

        /** @param nanos 경과 시간을 재는 단조 증가 시간원(기본 System::nanoTime) — 벽시계를 앞으로 맞춰도 살아 있는 값의 몫이 풀리지 않게 */
        StoreBudget(long capacity, LongSupplier nanos) {
            if (capacity <= 0) {
                throw new IllegalArgumentException("budget must be positive: " + capacity);
            }
            this.capacity = capacity;
            this.nanos = Objects.requireNonNull(nanos, "nanos");
        }

        /** Redis 메모리로 셀 양 — 키 + 값 + 고정 비용 + 값의 1/4(큰 값의 할당 구간 몫). */
        static long charge(int keyBytes, int valueBytes) {
            return keyBytes + valueBytes + PER_KEY_OVERHEAD + valueBytes / 4L;
        }

        /**
         * 지금부터 holdFor 동안 bytes 를 잡는다. 상한을 넘으면 null. 만료 시각은 잡을 때 정한다 — 미확정 상태가 없어(특별한 값으로 표시하지 않는다)
         * 음수 nanoTime 에서 넘침으로 몫이 풀리는 일이 없다. 넣는 값의 Redis 만료가 절대 시각이라, 그 시각 + 시계 차 여유를 holdFor 로 준다.
         */
        synchronized Hold reserve(long bytes, Duration holdFor) {
            return reserve(bytes, holdFor, null, null, -1);
        }

        /** @param key 넣는 키 · generation · generationNumber — 세대가 오르면 옛 세대 몫을 찾아 지우려고 같이 적는다 */
        synchronized Hold reserve(long bytes, Duration holdFor, String key, String generation, long generationNumber) {
            purge();
            if (used + bytes > capacity) {
                return null;
            }
            Hold hold = new Hold(bytes, nanos.getAsLong() + holdFor.toNanos(), key, generation, generationNumber);
            live.add(hold);
            used += bytes;
            return hold;
        }

        /** 보내지 않은 것이 확실하다(차단기가 막음) — 바로 돌려준다. */
        synchronized void release(Hold hold) {
            if (live.remove(hold)) {
                used -= hold.bytes;
            }
        }

        /** 아직 살아 있는 몫 중 그 세대의 옛 번호(current 미만)로 넣은 것. */
        synchronized List<Hold> olderThan(String generation, long current) {
            purge();
            return live.stream().filter(h -> h.key != null && generation.equals(h.generation) && h.generationNumber < current).toList();
        }

        synchronized long used() {
            purge();
            return used;
        }

        private void purge() {
            long now = nanos.getAsLong();
            while (!live.isEmpty() && live.peek().expiresAtNanos - now <= 0) {
                used -= live.poll().bytes;
            }
        }

        /** 잡은 몫 하나. */
        static final class Hold {
            private final long bytes;
            private final long expiresAtNanos;
            private final String key;
            private final String generation;
            private final long generationNumber;

            private Hold(long bytes, long expiresAtNanos, String key, String generation, long generationNumber) {
                this.bytes = bytes;
                this.expiresAtNanos = expiresAtNanos;
                this.key = key;
                this.generation = generation;
                this.generationNumber = generationNumber;
            }

            String key() {
                return key;
            }
        }

        /** 상한을 넘어 넣지 않았다 — 분마다 한 번만 남긴다. */
        synchronized void warnSkipped(String name) {
            long now = nanos.getAsLong();
            if (lastWarnNanos == Long.MIN_VALUE || now - lastWarnNanos >= 60_000_000_000L) {
                lastWarnNanos = now;
                log.warn("읽기 캐시 저장 상한({} bytes)을 넘어 넣지 않았다 — DB 로 답한다 cache={}", capacity, name);
            }
        }
    }
}
