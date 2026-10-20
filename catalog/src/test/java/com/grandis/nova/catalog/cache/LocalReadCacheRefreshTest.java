package com.grandis.nova.catalog.cache;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.common.redis.cache.ReadCache;
import com.grandis.nova.common.redis.cache.ReadCacheEvents;
import com.grandis.nova.common.redis.cache.ReadCacheEvents.Outcome;
import com.grandis.nova.common.redis.cache.ReadCacheEvents.RefreshResult;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        assertThat(events.outcomes).as("요청마다 하나").containsExactly(Outcome.LOAD, Outcome.HIT, Outcome.HIT_REFRESH, Outcome.HIT);
        assertThat(events.refreshes).as("갱신 작업마다 하나").containsExactly(RefreshResult.STORED);
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
        assertThat(events.refreshes).containsExactly(RefreshResult.FAILED);
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
        assertThat(events.refreshes).containsExactly(RefreshResult.FAILED);
    }

    @Test
    @DisplayName("큐에서 기다리는 사이 비웠으면(세대가 올랐으면) 갱신은 읽지 않는다")
    void lateRefreshSkipsAfterInvalidation() {
        LocalReadCache<String, String> cache = cache();
        cache.entry("k", () -> load("v1"));
        clock.advance(Duration.ofSeconds(4));
        cache.entry("k", () -> load("갱신"));
        cache.invalidateAll();
        tasks.runAll();
        assertThat(events.refreshes).containsExactly(RefreshResult.SKIPPED_NEWER);
        assertThat(loads.get()).as("갱신은 읽지 않았다").isEqualTo(1);
        cache.entry("k", () -> load("새 세대"));
        clock.advance(Duration.ofSeconds(4));
        cache.entry("k", () -> load("새 세대 갱신"));
        assertThat(tasks.pending()).as("새 세대 자리의 갱신은 막히지 않는다").isEqualTo(1);
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
    @DisplayName("갱신이 읽는 사이 비우기와 새 세대 적재가 있었으면 갱신은 그 값을 덮지 않는다 — 덮으면 다음 요청이 다시 읽는다")
    void refreshDoesNotOverwriteNewerGeneration() {
        LocalReadCache<String, String> cache = cache();
        cache.entry("k", () -> load("옛"));
        clock.advance(Duration.ofSeconds(4));
        cache.entry("k", () -> {
            cache.invalidateAll();   // 갱신이 DB 를 읽는 사이 상품이 바뀌어 비웠고
            cache.entry("k", () -> load("새"));   // 그 뒤 요청이 새 세대 값을 넣었다
            return load("갱신(옛 세대)");
        });
        tasks.runAll();
        assertThat(events.refreshes).containsExactly(RefreshResult.NOT_STORED);
        int before = loads.get();
        assertThat(cache.entry("k", () -> load("또")).value()).isEqualTo("새");
        assertThat(loads.get()).as("새 세대 값이 남아 다시 읽지 않는다").isEqualTo(before);
    }

    @Test
    @DisplayName("실행기가 차면 맡기지 않는다 — 요청은 들고 있던 값을 받고, 그 값이 만료될 때까지 그 키는 다시 맡기지 않는다(거절을 요청마다 세지 않는다)")
    void rejectedRefreshIsSkipped() {
        boolean[] reject = {true};
        AtomicInteger attempts = new AtomicInteger();
        LocalReadCache<String, String> cache = new LocalReadCache<>("test", TTL, 1024, String::length, clock, true, Duration.ofSeconds(5),
                command -> {
                    attempts.incrementAndGet();
                    if (reject[0]) {
                        throw new RejectedExecutionException("full");
                    }
                    tasks.execute(command);
                }, events);
        cache.entry("k", () -> load("v1"));
        clock.advance(Duration.ofMillis(4500));   // 80%(4초)보다 늦게 — 차단이 거절 시각이 아니라 값의 만료(5초)까지인지 가른다
        for (int i = 0; i < 3; i++) {
            assertThat(cache.entry("k", () -> load("v2")).value()).isEqualTo("v1");
        }
        assertThat(events.refreshes).as("거절은 한 번만 센다").containsExactly(RefreshResult.REJECTED);
        assertThat(attempts.get()).as("실행기에는 한 번만 맡기려 했다").isEqualTo(1);
        assertThat(events.outcomes.getLast()).as("맡기지 못한 요청은 바로 받음으로 센다").isEqualTo(Outcome.HIT);

        reject[0] = false;
        clock.advance(Duration.ofMillis(499));   // 만료 직전 — 차단은 그 값이 만료될 때까지다
        assertThat(cache.entry("k", () -> load("v2")).value()).as("아직 들고 있던 값").isEqualTo("v1");
        assertThat(tasks.pending()).as("실행기가 비어도 그 값이 만료되기 전에는 다시 맡기지 않는다").isZero();
        clock.advance(Duration.ofMillis(1));
        assertThat(cache.entry("k", () -> load("v2")).value()).as("만료 뒤 직접 읽기").isEqualTo("v2");
        clock.advance(Duration.ofSeconds(4));
        cache.entry("k", () -> load("v3"));
        assertThat(tasks.pending()).as("새 값의 주기에는 다시 맡긴다(차단이 풀렸다)").isEqualTo(1);
    }

    @Test
    @DisplayName("실행기가 거절 아닌 예외로 맡기지 못해도 그 값이 만료될 때까지 다시 맡기지 않는다")
    void failedSubmitIsNotRepeated() {
        AtomicInteger attempts = new AtomicInteger();
        LocalReadCache<String, String> cache = new LocalReadCache<>("test", TTL, 1024, String::length, clock, true, Duration.ofSeconds(5),
                command -> {
                    attempts.incrementAndGet();
                    throw new IllegalStateException("broken");
                }, events);
        cache.entry("k", () -> load("v1"));
        clock.advance(Duration.ofSeconds(4));
        for (int i = 0; i < 3; i++) {
            assertThat(cache.entry("k", () -> load("v2")).value()).isEqualTo("v1");
        }
        assertThat(attempts.get()).as("실행기에는 한 번만 맡기려 했다").isEqualTo(1);
        assertThat(events.refreshes).containsExactly(RefreshResult.REJECTED);
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

    @Test
    @DisplayName("갱신이 성공하면 다음 주기에 다시 맡긴다. 실패하면 그 값이 만료될 때까지 맡기지 않고, 만료 뒤 직접 읽은 새 값의 주기에는 다시 맡긴다")
    void refreshSlotIsReleasedAfterEachRun() {
        LocalReadCache<String, String> cache = cache();
        cache.entry("k", () -> load("v1"));
        clock.advance(Duration.ofSeconds(4));
        cache.entry("k", () -> load("v2"));
        tasks.runAll();   // 성공 — v2(읽은 시각 4초)
        clock.advance(Duration.ofMillis(4500));   // 8.5초 — 80%(8초)보다 늦게 실패시켜, 차단이 값의 만료(9초)까지인지 가른다
        cache.entry("k", () -> {
            throw new IllegalStateException("DB");
        });
        assertThat(tasks.pending()).as("성공 뒤 다음 주기").isEqualTo(1);
        tasks.runAll();   // 실패
        cache.entry("k", () -> load("v3"));
        assertThat(tasks.pending()).as("실패 뒤 — 그 값이 만료될 때까지 다시 맡기지 않는다").isZero();
        clock.advance(Duration.ofMillis(500));
        assertThat(cache.entry("k", () -> load("v4")).value()).as("만료 — 직접 읽는다").isEqualTo("v4");
        clock.advance(Duration.ofSeconds(4));
        cache.entry("k", () -> load("v5"));
        assertThat(tasks.pending()).as("새 값의 주기에는 다시 맡긴다(차단이 풀렸다)").isEqualTo(1);
    }

    @Test
    @DisplayName("넣지 못한 갱신(넣지 않는 값)은 그 값이 만료될 때까지 다시 맡기지 않는다 — 만료되면 요청이 직접 읽는다")
    void notStoredRefreshIsNotRepeated() {
        LocalReadCache<String, String> cache = cache();
        cache.entry("k", () -> load("v1"), value -> !value.isEmpty());
        clock.advance(Duration.ofSeconds(4));
        cache.entry("k", () -> load(""), value -> !value.isEmpty());
        tasks.runAll();
        assertThat(events.refreshes).containsExactly(RefreshResult.NOT_STORED);
        for (int i = 0; i < 3; i++) {
            assertThat(cache.entry("k", () -> load(""), value -> !value.isEmpty()).value()).isEqualTo("v1");
        }
        assertThat(tasks.pending()).as("만료 전에는 다시 맡기지 않는다").isZero();
        clock.advance(Duration.ofSeconds(1));
        assertThat(cache.entry("k", () -> load("v2"), value -> !value.isEmpty()).value()).as("만료 뒤 직접 읽기").isEqualTo("v2");
        clock.advance(Duration.ofSeconds(4));
        cache.entry("k", () -> load("v3"), value -> !value.isEmpty());
        assertThat(tasks.pending()).as("새 값의 주기에는 다시 맡긴다(차단이 풀렸다)").isEqualTo(1);
    }

    @Test
    @DisplayName("큐에서 늦게 돈 갱신은 그사이 더 새 값이 들어왔으면 읽지 않는다")
    void lateRefreshSkipsWhenNewerValueExists() {
        LocalReadCache<String, String> cache = cache();
        cache.entry("k", () -> load("v1"));
        clock.advance(Duration.ofSeconds(4));
        cache.entry("k", () -> load("갱신"));
        clock.advance(Duration.ofSeconds(1));
        assertThat(cache.entry("k", () -> load("직접")).value()).as("만료 — 요청이 직접 읽었다").isEqualTo("직접");
        tasks.runAll();
        assertThat(events.refreshes).containsExactly(RefreshResult.SKIPPED_NEWER);
        assertThat(loads.get()).as("갱신은 읽지 않았다").isEqualTo(2);
        assertThat(cache.entry("k", () -> load("다른 값")).value()).isEqualTo("직접");
    }

    @Test
    @DisplayName("실패한 갱신을 기다린 요청은 같은 예외를 받는다 — 직접 읽기가 실패했을 때와 같다")
    void waiterOnFailedRefreshGetsSameException() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        IllegalStateException failure = new IllegalStateException("DB");
        LocalReadCache<String, String> cache = new LocalReadCache<>("test", TTL, 1024, String::length, clock, true, Duration.ofSeconds(5),
                command -> new Thread(command).start(), events);
        cache.entry("k", () -> load("v1"));
        clock.advance(Duration.ofSeconds(4));
        cache.entry("k", () -> {
            started.countDown();
            await(release);
            throw failure;
        });
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        clock.advance(Duration.ofSeconds(1));
        CompletableFuture<ReadCache.Entry<String>> waiting = CompletableFuture.supplyAsync(() -> cache.entry("k", () -> load("직접")));
        awaitWaiting(cache);
        release.countDown();

        assertThat(waiting).failsWithin(Duration.ofSeconds(5)).withThrowableThat().havingRootCause().isSameAs(failure);
    }

    @Test
    @DisplayName("멈춘 갱신을 기다린 요청은 상한까지만 기다리고 503 — 직접 읽기를 기다릴 때와 같다")
    void waiterOnStuckRefreshGivesUpAfterTheCap() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        LocalReadCache<String, String> cache = new LocalReadCache<>("test", TTL, 1024, String::length, clock, true, Duration.ofMillis(200),
                command -> new Thread(command).start(), events);
        cache.entry("k", () -> load("v1"));
        clock.advance(Duration.ofSeconds(4));
        cache.entry("k", () -> {
            started.countDown();
            await(release);
            return load("v2");
        });
        try {
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            clock.advance(Duration.ofSeconds(1));
            assertThatThrownBy(() -> cache.entry("k", () -> load("직접")))
                    .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.errorCode()).isEqualTo(CommonErrorCode.DEPENDENCY_UNAVAILABLE));
            assertThat(loads.get()).as("기다린 요청은 읽지 않았다").isEqualTo(1);
        } finally {
            release.countDown();
        }
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
        final List<RefreshResult> refreshes = java.util.Collections.synchronizedList(new ArrayList<>());

        @Override
        public void refreshed(String cache, RefreshResult result) {
            refreshes.add(result);
        }

        @Override
        public void served(String cache, Outcome outcome) {
            outcomes.add(outcome);
        }

        @Override
        public void waited(String cache, Duration waited) {
        }
    }
}
