package com.grandis.nova.catalog.cache;

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
    @DisplayName("무게 상한을 넘으면 내보낸다 — 무게는 weigher(값 길이)로 센다")
    void evictsOverWeight() {
        LocalReadCache<String, String> cache = cache(true, 10);
        cache.entry("a", () -> load("12345678"));
        cache.entry("b", () -> load("12345678"));
        cache.cache().cleanUp();
        assertThat(cache.cache().estimatedSize()).isEqualTo(1);
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
