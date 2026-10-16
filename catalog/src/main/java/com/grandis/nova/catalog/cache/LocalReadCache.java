package com.grandis.nova.catalog.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.grandis.nova.common.redis.cache.ReadCache;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

/**
 * 인스턴스 메모리 읽기 캐시(Caffeine). Redis 읽기 캐시({@link ReadCache})와 같은 약속을 지킨다 — 정본은 DB, 값과 함께 DB 에서 읽은 시각을 준다
 * (응답의 max-age), 같은 키를 동시에 찾으면 loader 는 한 번, loader 의 예외는 넣지 않고 그대로 던진다, 꺼 두면 늘 loader.
 *
 * Redis 와 다른 점은 비우기가 이 인스턴스에만 닿는다는 것이다 — 다른 인스턴스는 TTL 이 지나야 새 값이다. 그래서 TTL 이 짧거나(상품 목록 5초)
 * 고치는 쪽이 없는(카테고리) 조회만 둔다. 비우기는 세대를 올리고 다 버린다 — 비우기 전에 DB 를 읽기 시작한 조회는 옛 세대라 넣지 않는다
 * (Redis 의 "옛 세대 키" 와 같은 뜻).
 *
 * @param <K> 키 — 값이 같으면 같은 조회(레코드)
 * @param <V> 값 — 바뀌지 않는(불변) 객체. 같이 기다린 요청들이 한 객체를 나눠 쓴다
 */
public final class LocalReadCache<K, V> {

    private final String name;
    private final Duration ttl;
    private final Clock clock;
    private final boolean enabled;
    private final Cache<K, Loaded<V>> cache;
    private final AtomicLong generation = new AtomicLong();

    /**
     * @param maximumWeight 이 인스턴스가 들고 있는 양의 상한 — weigher 단위(바이트로 셈)
     * @param weigher       값 하나의 무게. 넣을 때 한 번 부른다
     */
    public LocalReadCache(String name, Duration ttl, long maximumWeight, ToIntFunction<V> weigher, Clock clock, boolean enabled) {
        this.name = name;
        this.ttl = ttl;
        this.clock = clock;
        this.enabled = enabled;
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(ttl)
                .maximumWeight(maximumWeight)
                .weigher((K key, Loaded<V> loaded) -> Math.max(1, weigher.applyAsInt(loaded.entry().value())))
                .recordStats()
                .build();
    }

    public String name() {
        return name;
    }

    public Duration ttl() {
        return ttl;
    }

    /** 지표 등록 · 시험용. */
    Cache<K, Loaded<V>> cache() {
        return cache;
    }

    /**
     * 키의 값과 그 값을 DB 에서 읽은 시각. 없으면 loader 로 만들고, storable 이 참이고 그사이 비우기가 없었으면 넣는다.
     * 읽은 시각은 loader 를 부르기 직전이다(Redis 캐시 · {@link ReadCache#uncached} 와 같은 기준).
     */
    public ReadCache.Entry<V> entry(K key, Supplier<V> loader, Predicate<V> storable) {
        if (!enabled) {
            Instant at = clock.instant();
            return new ReadCache.Entry<>(loader.get(), at);
        }
        Object[] loadedHere = new Object[1];
        Loaded<V> found = cache.get(key, ignored -> {
            long started = generation.get();
            Instant at = clock.instant();
            ReadCache.Entry<V> entry = new ReadCache.Entry<>(loader.get(), at);
            loadedHere[0] = entry;
            return storable.test(entry.value()) && generation.get() == started ? new Loaded<>(entry) : null;
        });
        if (found != null) {
            return found.entry();
        }
        @SuppressWarnings("unchecked")
        ReadCache.Entry<V> entry = (ReadCache.Entry<V>) loadedHere[0];
        return entry;
    }

    public ReadCache.Entry<V> entry(K key, Supplier<V> loader) {
        return entry(key, loader, value -> value != null);
    }

    /** 지금 버린다 — 진행 중인 조회는 옛 세대라 넣지 않는다. */
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

    /** 넣은 값. Caffeine 은 null 을 넣지 않으므로 값을 감싼다. */
    record Loaded<V>(ReadCache.Entry<V> entry) {
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
