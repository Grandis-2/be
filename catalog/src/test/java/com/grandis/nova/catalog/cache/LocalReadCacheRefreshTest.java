package com.grandis.nova.catalog.cache;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.redis.cache.ReadCache;
import com.grandis.nova.common.redis.cache.ReadCacheEvents;
import com.grandis.nova.common.redis.cache.ReadCacheEvents.Outcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 메모리 읽기 캐시의 미리 갱신. 갱신 실행기는 맡긴 일을 모아 두었다가 시험이 정한 때 돌린다 — 요청이 갱신을 기다리지 않는지, 갱신이 언제 값을 바꾸는지를
 * 시험이 순서대로 정한다. 앱 시계는 손으로 옮긴다(TTL 5초, 미리 갱신은 읽은 지 4초부터).
 */
@DisplayName("메모리 읽기 캐시 — 미리 갱신")
class LocalReadCacheRefreshTest {

    static final Instant NOW = Instant.parse("2026-10-20T00:00:00Z");
    static final Duration TTL = Duration.ofSeconds(5);

    final LocalReadCacheTest.MutableClock clock = new LocalReadCacheTest.MutableClock(NOW);
    final Tasks tasks = new Tasks();
    final Events events = new Events();
    final AtomicInteger loads = new AtomicInteger();

    @Test
    @DisplayName("읽은 지 TTL 의 80% 전에는 갱신하지 않는다 — 그 뒤 요청은 들고 있던 값을 바로 받고 갱신을 하나 맡긴다")
    void refreshesOnlyAfterEightyPercent() {
        LocalReadCache<String, String> cache = cache();
        cache.entry("k", () -> load("v1"));
        clock.advance(Duration.ofMillis(3999));
        assertThat(cache.entry("k", () -> load("v2")).value()).isEqualTo("v1");
        assertThat(tasks.pending()).as("4초 전").isZero();

        clock.advance(Duration.ofMillis(1));
        assertThat(cache.entry("k", () -> load("v2")).value()).as("요청은 기다리지 않고 들고 있던 값").isEqualTo("v1");
        assertThat(tasks.pending()).isEqualTo(1);
        assertThat(loads.get()).as("갱신은 아직 돌지 않았다").isEqualTo(1);

        tasks.runAll();
        assertThat(loads.get()).isEqualTo(2);
        ReadCache.Entry<String> refreshed = cache.entry("k", () -> load("v3"));
        assertThat(refreshed.value()).isEqualTo("v2");
        assertThat(refreshed.loadedAt()).as("갱신을 시작한 시각").isEqualTo(NOW.plusSeconds(4));
        assertThat(events.outcomes).containsExactly(Outcome.LOAD, Outcome.HIT, Outcome.HIT_REFRESH, Outcome.REFRESHED, Outcome.HIT);
    }

    @Test
    @DisplayName("같은 키 · 세대의 갱신은 하나만 맡긴다 — 맡긴 갱신이 돌기 전에 요청이 몇 번 와도")
    void oneRefreshPerKey() {
        LocalReadCache<String, String> cache = cache();
        cache.entry("k", () -> load("v1"));
        clock.advance(Duration.ofSeconds(4));
        for (int i = 0; i < 5; i++) {
            cache.entry("k", () -> load("v2"));
        }
        assertThat(tasks.pending()).isEqualTo(1);
        tasks.runAll();
        assertThat(loads.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("갱신이 실패해도 들고 있던 값의 만료는 늘지 않는다 — 만료 뒤 요청이 직접 읽는다")
    void failedRefreshDoesNotExtend() {
        LocalReadCache<String, String> cache = cache();
        cache.entry("k", () -> load("v1"));
        clock.advance(Duration.ofSeconds(4));
        cache.entry("k", () -> {
            loads.incrementAndGet();
            throw new IllegalStateException("DB");
        });
        tasks.runAll();
        assertThat(cache.entry("k", () -> load("v2")).value()).as("만료 전 — 들고 있던 값").isEqualTo("v1");

        clock.advance(Duration.ofSeconds(1));
        assertThat(cache.entry("k", () -> load("v3")).value()).as("만료 — 요청이 직접 읽는다").isEqualTo("v3");
        assertThat(events.outcomes).contains(Outcome.REFRESH_FAILED);
        assertThat(events.outcomes.getLast()).isEqualTo(Outcome.LOAD);
    }

    @Test
    @DisplayName("업무 오류(404 등)로 끝난 갱신도 값을 늘리지 않고, 요청에는 들고 있던 값이 그대로 간다")
    void businessFailureDuringRefresh() {
        LocalReadCache<String, String> cache = cache();
        cache.entry("k", () -> load("v1"));
        clock.advance(Duration.ofSeconds(4));
        cache.entry("k", () -> {
            throw new BusinessException(com.grandis.nova.common.CommonErrorCode.NOT_FOUND);
        });
        tasks.runAll();
        assertThat(cache.entry("k", () -> load("v2")).value()).isEqualTo("v1");
        assertThat(events.outcomes).contains(Outcome.REFRESH_FAILED);
    }

    @Test
    @DisplayName("갱신이 도는 중에 값이 만료되면 그 뒤 요청은 갱신을 기다린다 — loader 는 갱신의 한 번뿐")
    void expiredWhileRefreshingWaitsForIt() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        LocalReadCache<String, String> cache = new LocalReadCache<>("test", TTL, 1024, String::length, clock, true, Duration.ofSeconds(5),
                command -> new Thread(command).start(), events);
        cache.entry("k", () -> load("v1"));
        clock.advance(Duration.ofSeconds(4));
        cache.entry("k", () -> {
            started.countDown();
            await(release);
            return load("v2");
        });
        assertThat(started.await(5, TimeUnit.SECONDS)).as("갱신이 시작됐다").isTrue();
        clock.advance(Duration.ofSeconds(1));   // 만료 — 갱신은 아직 도는 중
        CompletableFuture<ReadCache.Entry<String>> waiting = CompletableFuture.supplyAsync(() -> cache.entry("k", () -> load("직접")));
        awaitWaiting(cache);
        release.countDown();

        assertThat(waiting.get(5, TimeUnit.SECONDS).value()).as("갱신의 값").isEqualTo("v2");
        assertThat(loads.get()).as("처음 읽기 + 갱신 — 기다린 요청은 읽지 않았다").isEqualTo(2);
        assertThat(events.outcomes).contains(Outcome.WAIT);
    }

    @Test
    @DisplayName("비운 뒤 끝난 갱신의 값은 보이지 않는다 — 갱신은 맡길 때의 세대를 든다")
    void refreshStartedBeforeInvalidationIsNotServed() {
        LocalReadCache<String, String> cache = cache();
        cache.entry("k", () -> load("옛"));
        clock.advance(Duration.ofSeconds(4));
        cache.entry("k", () -> load("갱신(옛 세대)"));
        cache.invalidateAll();
        tasks.runAll();
        assertThat(cache.entry("k", () -> load("새")).value()).isEqualTo("새");
    }

    @Test
    @DisplayName("실행기가 차면 맡기지 않는다 — 요청은 들고 있던 값을 받고, 다음 요청이 다시 맡긴다")
    void rejectedRefreshIsSkipped() {
        boolean[] reject = {true};
        LocalReadCache<String, String> cache = new LocalReadCache<>("test", TTL, 1024, String::length, clock, true, Duration.ofSeconds(5),
                command -> {
                    if (reject[0]) {
                        throw new RejectedExecutionException("full");
                    }
                    tasks.execute(command);
                }, events);
        cache.entry("k", () -> load("v1"));
        clock.advance(Duration.ofSeconds(4));
        assertThat(cache.entry("k", () -> load("v2")).value()).isEqualTo("v1");
        assertThat(events.outcomes).contains(Outcome.REFRESH_REJECTED);

        reject[0] = false;
        cache.entry("k", () -> load("v2"));
        assertThat(tasks.pending()).as("거절된 자리는 비었다 — 다음 요청이 다시 맡긴다").isEqualTo(1);
    }

    @Test
    @DisplayName("실행기가 없으면(끈 캐시 · 시험) 미리 갱신하지 않는다")
    void noRefresherNoRefresh() {
        LocalReadCache<String, String> cache = new LocalReadCache<>("test", TTL, 1024, String::length, clock, true);
        cache.entry("k", () -> load("v1"));
        clock.advance(Duration.ofSeconds(4));
        assertThat(cache.entry("k", () -> load("v2")).value()).isEqualTo("v1");
        assertThat(loads.get()).isEqualTo(1);
    }

    private LocalReadCache<String, String> cache() {
        return new LocalReadCache<>("test", TTL, 1024, String::length, clock, true, Duration.ofSeconds(5), tasks, events);
    }

    private String load(String value) {
        loads.incrementAndGet();
        return value;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void awaitWaiting(LocalReadCache<String, String> cache) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (cache.waiting("k") == 0 && System.nanoTime() < until) {
            Thread.sleep(5);
        }
        assertThat(cache.waiting("k")).as("만료 뒤 요청이 갱신을 기다리는 중").isPositive();
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
        final List<Outcome> outcomes = java.util.Collections.synchronizedList(new ArrayList<>());

        @Override
        public void served(String cache, Outcome outcome) {
            outcomes.add(outcome);
        }

        @Override
        public void waited(String cache, Duration waited) {
        }
    }
}
