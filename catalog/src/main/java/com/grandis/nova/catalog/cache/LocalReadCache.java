package com.grandis.nova.catalog.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.common.redis.cache.ReadCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

/**
 * 인스턴스 메모리 읽기 캐시(Caffeine). Redis 읽기 캐시({@link ReadCache})와 같은 약속을 지킨다.
 * <ul>
 *   <li>정본은 DB 다. 값과 함께 DB 에서 읽은 시각(loader 를 부르기 직전)을 준다 — 응답의 max-age 가 이 시각부터 TTL 안에서 남은 시간이다.</li>
 *   <li>값은 읽은 시각 + TTL 에 사라진다(넣은 시각이 아니다 — 읽는 데 걸린 시간도 TTL 에서 뺀다). 꺼낼 때도 앱 시계로 한 번 더 본다.</li>
 *   <li>같은 키를 동시에 찾으면 loader 는 이 인스턴스에서 한 번이고, 넣지 않는 값(storable 거짓)이든 예외든 기다린 쪽이 같은 결과를 받는다.
 *       기다리는 쪽은 {@link #WAIT_FOR_LOAD} 까지만 — 넘으면 503(다시 시도). loader 는 Caffeine 의 계산 밖에서 돌아 다른 키를 막지 않는다.</li>
 *   <li>꺼 두면 늘 loader 다.</li>
 * </ul>
 * Redis 와 다른 점은 비우기가 이 인스턴스에만 닿는다는 것이다 — 다른 인스턴스는 TTL 이 지나야 새 값이다. 그래서 TTL 이 짧거나(상품 목록 5초)
 * 고치는 쪽이 없는(카테고리) 조회만 둔다. 비우기는 세대를 올리고 다 버린다. 넣은 값은 읽기 시작할 때의 세대를 들고 있어, 꺼낼 때 세대가 다르면
 * 버리고 다시 읽는다 — 비우기 전에 DB 를 읽기 시작한 조회가 비운 뒤에 넣은 옛 값도 보이지 않는다(Redis 의 "옛 세대 키" 와 같은 뜻).
 *
 * @param <K> 키 — 값이 같으면 같은 조회(레코드)
 * @param <V> 값 — 바뀌지 않는(불변) 객체. 같이 기다린 요청들이 한 객체를 나눠 쓴다
 */
public final class LocalReadCache<K, V> {

    /** 같은 키의 진행 중 조회를 같이 받는 쪽이 기다리는 상한 — Redis 읽기 캐시와 같다. */
    static final Duration WAIT_FOR_LOAD = Duration.ofSeconds(5);

    private static final Logger log = LoggerFactory.getLogger(LocalReadCache.class);

    private final String name;
    private final Duration ttl;
    private final Clock clock;
    private final boolean enabled;
    private final ToIntFunction<V> weigher;
    private final Duration waitForLoad;
    private final Cache<K, Loaded<V>> cache;
    private final ConcurrentHashMap<K, CompletableFuture<ReadCache.Entry<V>>> loading = new ConcurrentHashMap<>();
    private final AtomicLong generation = new AtomicLong();

    /**
     * @param maximumWeight 이 인스턴스가 들고 있는 양의 상한 — weigher 단위
     * @param weigher       값 하나의 무게. 넣기 전에 한 번 부른다
     */
    public LocalReadCache(String name, Duration ttl, long maximumWeight, ToIntFunction<V> weigher, Clock clock, boolean enabled) {
        this(name, ttl, maximumWeight, weigher, clock, enabled, WAIT_FOR_LOAD);
    }

    LocalReadCache(String name, Duration ttl, long maximumWeight, ToIntFunction<V> weigher, Clock clock, boolean enabled, Duration waitForLoad) {
        this.name = name;
        this.ttl = ttl;
        this.clock = clock;
        this.enabled = enabled;
        this.weigher = weigher;
        this.waitForLoad = waitForLoad;
        this.cache = Caffeine.newBuilder()
                .expireAfter(new UntilStale<K, V>())
                .maximumWeight(maximumWeight)
                .weigher((K key, Loaded<V> loaded) -> loaded.weight())
                .build();
    }

    public String name() {
        return name;
    }

    public Duration ttl() {
        return ttl;
    }

    /** 시험용. */
    Cache<K, Loaded<V>> cache() {
        return cache;
    }

    /** 시험용 — 그 키의 진행 중 조회를 기다리는 요청 수(추정). 진행 중인 조회가 없으면 0. */
    int waiting(K key) {
        CompletableFuture<ReadCache.Entry<V>> running = loading.get(key);
        return running == null ? 0 : running.getNumberOfDependents();
    }

    /** 키의 값과 그 값을 DB 에서 읽은 시각. 없으면 loader 로 만들고, storable 이 참이면 넣는다. */
    public ReadCache.Entry<V> entry(K key, Supplier<V> loader, Predicate<V> storable) {
        if (!enabled) {
            Instant at = clock.instant();
            return new ReadCache.Entry<>(loader.get(), at);
        }
        Loaded<V> found = cache.getIfPresent(key);
        if (found != null) {
            if (found.generation() == generation.get() && fresh(found.entry())) {
                return found.entry();
            }
            cache.asMap().remove(key, found);   // 비우기 전 세대 · 시계로 지난 값
        }
        return load(key, loader, storable);
    }

    public ReadCache.Entry<V> entry(K key, Supplier<V> loader) {
        return entry(key, loader, value -> true);
    }

    /** 지금 버린다 — 진행 중인 조회가 뒤에 넣는 값은 옛 세대라 꺼낼 때 버린다. */
    public void invalidateAll() {
        generation.incrementAndGet();
        cache.invalidateAll();
    }

    /**
     * 트랜잭션 안이면 그 커밋 뒤에 한 번(같은 트랜잭션에서 여러 번 불러도), 밖이면 바로 버린다. 롤백된 수정으로 비우지 않는다.
     * 커밋 전에는 버리지 않는다 — 커밋 전에 버리면 그사이 다른 조회가 커밋 전 DB 를 읽어 다시 넣는다.
     */
    public void invalidateAfterCommit() {
        if (!enabled) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            invalidateAll();
            return;
        }
        for (TransactionSynchronization registered : TransactionSynchronizationManager.getSynchronizations()) {
            if (registered instanceof InvalidateOnCommit mine && mine.owner == this) {
                return;
            }
        }
        TransactionSynchronizationManager.registerSynchronization(new InvalidateOnCommit(this));
    }

    private boolean fresh(ReadCache.Entry<V> entry) {
        return clock.instant().isBefore(entry.loadedAt().plus(ttl));
    }

    /** 같은 키의 loader 를 한 번만 부르고 나머지는 그 결과(또는 예외)를 같이 받는다 — Redis 읽기 캐시의 load 와 같은 모양. */
    private ReadCache.Entry<V> load(K key, Supplier<V> loader, Predicate<V> storable) {
        CompletableFuture<ReadCache.Entry<V>> mine = new CompletableFuture<>();
        CompletableFuture<ReadCache.Entry<V>> running = loading.putIfAbsent(key, mine);
        if (running != null) {
            try {
                return running.get(waitForLoad.toMillis(), TimeUnit.MILLISECONDS);
            } catch (ExecutionException e) {
                throw e.getCause() instanceof RuntimeException runtime ? runtime : new IllegalStateException(e.getCause());
            } catch (TimeoutException e) {
                log.warn("메모리 읽기 캐시 — 같은 키의 조회가 {} 안에 끝나지 않아 503 cache={}", waitForLoad, name);
                throw new BusinessException(CommonErrorCode.DEPENDENCY_UNAVAILABLE);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BusinessException(CommonErrorCode.DEPENDENCY_UNAVAILABLE);
            }
        }
        try {
            long started = generation.get();
            Instant loadedAt = clock.instant();   // 읽기 전 시각 — 응답의 나이를 짧게 잡지 않게
            ReadCache.Entry<V> entry = new ReadCache.Entry<>(loader.get(), loadedAt);
            mine.complete(entry);
            if (entry.value() != null && storable.test(entry.value())) {
                store(key, entry, started);
            }
            return entry;
        } catch (RuntimeException | Error e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            loading.remove(key, mine);
        }
    }

    private void store(K key, ReadCache.Entry<V> entry, long started) {
        int weight;
        try {
            weight = Math.max(1, weigher.applyAsInt(entry.value()));
        } catch (RuntimeException e) {
            log.warn("메모리 읽기 캐시에 넣을 값의 무게를 못 셌다 — 넣지 않는다 cache={}", name, e);
            return;
        }
        Duration left = ttl.minus(Duration.between(entry.loadedAt(), clock.instant()));
        if (!left.isNegative() && !left.isZero()) {
            cache.put(key, new Loaded<>(entry, started, weight, left.toNanos()));
        }
    }

    /**
     * 넣은 값 — 읽기 시작할 때의 세대 · 무게 · 넣을 때 남은 TTL 을 같이 든다.
     *
     * @param ttlNanos 넣을 때 남은 TTL — 읽은 시각 + TTL 에 사라지게 Caffeine 의 만료로 쓴다
     */
    record Loaded<V>(ReadCache.Entry<V> entry, long generation, int weight, long ttlNanos) {
    }

    /** 넣을 때 남은 TTL 뒤에 만료(읽은 시각 기준). 읽기 · 덮어쓰기는 만료를 늘리지 않는다. */
    private static final class UntilStale<K, V> implements Expiry<K, Loaded<V>> {

        @Override
        public long expireAfterCreate(K key, Loaded<V> value, long currentTime) {
            return value.ttlNanos();
        }

        @Override
        public long expireAfterUpdate(K key, Loaded<V> value, long currentTime, long currentDuration) {
            return value.ttlNanos();
        }

        @Override
        public long expireAfterRead(K key, Loaded<V> value, long currentTime, long currentDuration) {
            return currentDuration;
        }
    }

    private static final class InvalidateOnCommit implements TransactionSynchronization {

        private final LocalReadCache<?, ?> owner;

        private InvalidateOnCommit(LocalReadCache<?, ?> owner) {
            this.owner = owner;
        }

        @Override
        public void afterCommit() {
            owner.invalidateAll();
        }
    }
}
