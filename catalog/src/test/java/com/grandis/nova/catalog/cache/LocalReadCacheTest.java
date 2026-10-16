package com.grandis.nova.catalog.cache;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.common.redis.cache.ReadCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("인스턴스 메모리 읽기 캐시")
class LocalReadCacheTest {

    static final Instant NOW = Instant.parse("2026-10-16T00:00:00Z");

    final AtomicInteger loads = new AtomicInteger();

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("두 번째부터 loader 없이 같은 값 — 읽은 시각은 처음 loader 를 부르기 직전")
    void hitsAfterFirstLoad() {
        LocalReadCache<String, String> cache = cache(true, 1024);
        ReadCache.Entry<String> first = cache.entry("k", () -> load("v"));
        ReadCache.Entry<String> second = cache.entry("k", () -> load("다른 값"));
        assertThat(second.value()).isEqualTo("v");
        assertThat(second.loadedAt()).isEqualTo(first.loadedAt()).isEqualTo(NOW);
        assertThat(loads.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("넣지 않는 값(storable 거짓) · loader 의 예외는 넣지 않는다 — 다음 조회가 다시 읽는다")
    void notStoredValuesAndFailuresAreReloaded() {
        LocalReadCache<String, String> cache = cache(true, 1024);
        assertThat(cache.entry("empty", () -> load(""), value -> !value.isEmpty()).value()).isEmpty();
        cache.entry("empty", () -> load(""), value -> !value.isEmpty());
        assertThat(loads.get()).isEqualTo(2);

        assertThatThrownBy(() -> cache.entry("boom", () -> {
            throw new IllegalStateException("DB");
        })).hasMessage("DB");
        assertThat(cache.entry("boom", () -> load("복구")).value()).isEqualTo("복구");
    }

    @Test
    @DisplayName("꺼 두면 늘 loader — 넣지 않는다")
    void disabledAlwaysLoads() {
        LocalReadCache<String, String> cache = cache(false, 1024);
        cache.entry("k", () -> load("v"));
        cache.entry("k", () -> load("v"));
        assertThat(loads.get()).isEqualTo(2);
        cache.invalidateAfterCommit();   // 꺼 두면 아무것도 하지 않는다
    }

    @Test
    @DisplayName("같은 키를 동시에 찾으면 loader 는 한 번 — 기다린 쪽도 같은 값")
    void concurrentMissLoadsOnce() throws Exception {
        LocalReadCache<String, String> cache = cache(true, 1024);
        CountDownLatch inLoader = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = pool.submit(() -> cache.entry("k", () -> {
                inLoader.countDown();
                await(release);
                return load("v");
            }).value());
            assertThat(inLoader.await(5, TimeUnit.SECONDS)).isTrue();
            Future<String> second = pool.submit(() -> cache.entry("k", () -> load("두 번째")).value());
            awaitWaiting(cache, "k", 1);   // 둘째가 진행 중 조회에 붙은 뒤에 푼다 — 늦게 와 적중으로 통과하지 않게
            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo("v");
            assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo("v");
            assertThat(loads.get()).isEqualTo(1);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("비우기 전에 읽기 시작한 조회는 넣지 않는다 — 그 응답은 받되, 다음 조회는 새로 읽는다")
    void loadStartedBeforeInvalidationIsNotStored() throws Exception {
        LocalReadCache<String, String> cache = cache(true, 1024);
        CountDownLatch inLoader = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<String> stale = pool.submit(() -> cache.entry("k", () -> {
                inLoader.countDown();
                await(release);
                return load("옛 값");
            }).value());
            assertThat(inLoader.await(5, TimeUnit.SECONDS)).isTrue();
            cache.invalidateAll();   // 수정이 커밋됐다
            release.countDown();
            assertThat(stale.get(5, TimeUnit.SECONDS)).isEqualTo("옛 값");
            assertThat(cache.entry("k", () -> load("새 값")).value()).isEqualTo("새 값");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("비운 뒤에 온 요청은 비우기 전에 시작된 진행 중 조회에 붙지 않는다 — 새로 읽어 새 값(loader 두 번). 앞 요청은 옛 값")
    void requestAfterInvalidationDoesNotJoinStaleLoad() throws Exception {
        LocalReadCache<String, String> cache = cache(true, 1024);
        CountDownLatch inLoader = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<String> leader = pool.submit(() -> cache.entry("k", () -> {
                inLoader.countDown();
                await(release);
                return load("옛 값");
            }).value());
            assertThat(inLoader.await(5, TimeUnit.SECONDS)).isTrue();
            cache.invalidateAll();   // 수정이 커밋됐다
            assertThat(cache.entry("k", () -> load("새 값")).value()).isEqualTo("새 값");
            release.countDown();
            assertThat(leader.get(5, TimeUnit.SECONDS)).isEqualTo("옛 값");
            assertThat(loads.get()).isEqualTo(2);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("기다림 상한은 Redis 읽기 캐시와 같은 5초다(공개 생성자)")
    void waitLimitIsFiveSeconds() {
        assertThat(cache(true, 1024).waitForLoad()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("트랜잭션 안의 비우기는 커밋 뒤에 한 번 — 커밋 전에는 그대로, 롤백이면 비우지 않는다")
    void invalidatesAfterCommitOnly() {
        LocalReadCache<String, String> cache = cache(true, 1024);
        cache.entry("k", () -> load("v"));

        TransactionSynchronizationManager.initSynchronization();
        cache.invalidateAfterCommit();
        cache.invalidateAfterCommit();
        assertThat(TransactionSynchronizationManager.getSynchronizations()).as("트랜잭션당 하나").hasSize(1);
        assertThat(cache.entry("k", () -> load("커밋 전")).value()).as("커밋 전에는 그대로").isEqualTo("v");
        TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        assertThat(cache.entry("k", () -> load("롤백 뒤")).value()).as("롤백이면 그대로").isEqualTo("v");
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        assertThat(cache.entry("k", () -> load("커밋 뒤")).value()).isEqualTo("커밋 뒤");
    }

    @Test
    @DisplayName("넣지 않는 값 · 예외도 같은 키를 동시에 찾은 쪽은 loader 를 다시 부르지 않고 같은 결과를 받는다")
    void concurrentMissSharesNotStoredResultAndFailure() throws Exception {
        LocalReadCache<String, String> cache = cache(true, 1024);
        assertThat(sharedConcurrently(cache, () -> load(""))).containsOnly("");
        assertThat(loads.get()).as("빈 결과도 한 번").isEqualTo(1);

        loads.set(0);
        assertThat(sharedConcurrently(cache, () -> {
            loads.incrementAndGet();
            throw new IllegalStateException("DB");
        })).containsOnly("THREW DB");
        assertThat(loads.get()).as("예외도 한 번").isEqualTo(1);
    }

    @Test
    @org.junit.jupiter.api.Timeout(10)
    @DisplayName("같은 키의 조회를 기다리는 쪽은 상한까지만 — 넘으면 503(다시 시도)")
    void waitingBeyondLimitIs503() throws Exception {
        LocalReadCache<String, String> cache = new LocalReadCache<>("test", Duration.ofSeconds(5), 1024, String::length,
                Clock.fixed(NOW, ZoneOffset.UTC), true, Duration.ofMillis(100));
        CountDownLatch inLoader = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            pool.submit(() -> cache.entry("k", () -> {
                inLoader.countDown();
                await(release);
                return load("v");
            }));
            assertThat(inLoader.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> cache.entry("k", () -> load("x")))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).errorCode()).isEqualTo(CommonErrorCode.DEPENDENCY_UNAVAILABLE));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("한 키의 loader 가 도는 동안 다른 키는 기다리지 않는다")
    void otherKeysAreNotBlocked() throws Exception {
        LocalReadCache<String, String> cache = cache(true, 1024);
        CountDownLatch inLoader = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            pool.submit(() -> cache.entry("Aa", () -> {   // "Aa" 와 "BB" 는 hashCode 가 같다 — 같은 해시 빈이어도 막지 않는다
                inLoader.countDown();
                await(release);
                return load("느림");
            }));
            assertThat(inLoader.await(5, TimeUnit.SECONDS)).isTrue();
            long started = System.nanoTime();
            assertThat(cache.entry("BB", () -> load("바로")).value()).isEqualTo("바로");
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(500));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("만료는 읽은 시각 + TTL — 읽는 데 걸린 시간도 TTL 에서 뺀다(넣은 시각부터가 아니다)")
    void expiresFromLoadedAt() {
        MutableClock clock = new MutableClock(NOW);
        LocalReadCache<String, String> cache = new LocalReadCache<>("test", Duration.ofSeconds(5), 1024, String::length, clock, true);
        cache.entry("k", () -> {
            clock.advance(Duration.ofSeconds(4));   // DB 가 4초 걸렸다
            return load("v");
        });
        clock.advance(Duration.ofMillis(999));
        assertThat(cache.entry("k", () -> load("x")).value()).as("읽은 지 4.999초").isEqualTo("v");
        clock.advance(Duration.ofMillis(1));
        assertThat(cache.entry("k", () -> load("새 값")).value()).as("읽은 지 5초 — 넣은 지는 1초").isEqualTo("새 값");
    }

    @Test
    @DisplayName("무게를 못 세면 넣지 않는다 — 응답은 그대로")
    void weigherFailureSkipsStoring() {
        LocalReadCache<String, String> cache = new LocalReadCache<>("test", Duration.ofSeconds(5), 1024, value -> {
            throw new IllegalStateException("직렬화 실패");
        }, Clock.fixed(NOW, ZoneOffset.UTC), true);
        assertThat(cache.entry("k", () -> load("v")).value()).isEqualTo("v");
        assertThat(cache.entry("k", () -> load("v")).value()).isEqualTo("v");
        assertThat(loads.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("무게를 세는 사이에 비우면 늦게 넣은 옛 값도 보이지 않는다 — 넣은 값의 세대가 꺼낼 때와 다르다")
    void lateStoreAfterInvalidationIsIgnored() throws Exception {
        CountDownLatch weighing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        LocalReadCache<String, String> cache = new LocalReadCache<>("test", Duration.ofSeconds(5), 1024, value -> {
            if (value.equals("옛 값")) {
                weighing.countDown();
                await(release);
            }
            return value.length();
        }, Clock.fixed(NOW, ZoneOffset.UTC), true);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> stale = pool.submit(() -> cache.entry("k", () -> load("옛 값")));
            assertThat(weighing.await(5, TimeUnit.SECONDS)).isTrue();
            cache.invalidateAll();
            release.countDown();
            stale.get(5, TimeUnit.SECONDS);
            assertThat(cache.entry("k", () -> load("새 값")).value()).isEqualTo("새 값");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("무게 상한을 넘으면 내보낸다 — 무게는 weigher(값 길이)로 센다")
    void evictsOverWeight() {
        LocalReadCache<String, String> cache = cache(true, 10);
        cache.entry("a", () -> load("12345678"));
        cache.entry("b", () -> load("12345678"));
        cache.cache().cleanUp();
        assertThat(cache.cache().estimatedSize()).isEqualTo(1);
    }

    /** 같은 키를 네 스레드가 동시에 찾는다 — 첫 loader 가 도는 동안 나머지가 들어오게. 결과(예외면 "THREW 메시지")를 모은다. */
    private java.util.List<String> sharedConcurrently(LocalReadCache<String, String> cache, java.util.function.Supplier<String> loader) throws Exception {
        CountDownLatch inLoader = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            java.util.List<Future<String>> results = new java.util.ArrayList<>();
            results.add(pool.submit(() -> call(cache, () -> {
                inLoader.countDown();
                await(release);
                return loader.get();
            })));
            assertThat(inLoader.await(5, TimeUnit.SECONDS)).isTrue();
            for (int i = 0; i < 3; i++) {
                results.add(pool.submit(() -> call(cache, loader)));
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            awaitWaiting(cache, "shared", 3);   // 나머지 셋이 진행 중 조회에 붙을 때까지
            release.countDown();
            java.util.List<String> values = new java.util.ArrayList<>();
            for (Future<String> result : results) {
                values.add(result.get(5, TimeUnit.SECONDS));
            }
            return values;
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    private static void awaitWaiting(LocalReadCache<String, String> cache, String key, int count) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (cache.waiting(key) < count) {
            assertThat(System.nanoTime()).as("%d 개가 붙지 않았다", count).isLessThan(deadline);
            Thread.onSpinWait();
        }
    }

    private static String call(LocalReadCache<String, String> cache, java.util.function.Supplier<String> loader) {
        try {
            return cache.entry("shared", loader, value -> !value.isEmpty()).value();
        } catch (IllegalStateException e) {
            return "THREW " + e.getMessage();
        }
    }

    /** 시험이 앞으로 돌리는 시계. */
    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private LocalReadCache<String, String> cache(boolean enabled, long maximumWeight) {
        return new LocalReadCache<>("test", Duration.ofSeconds(5), maximumWeight, String::length, Clock.fixed(NOW, ZoneOffset.UTC), enabled);
    }

    private String load(String value) {
        loads.incrementAndGet();
        return value;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("기다림 초과");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
