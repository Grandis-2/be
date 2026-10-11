package com.grandis.nova.common.outbox;

import com.grandis.nova.common.outbox.support.OutboxIntegrationTest;
import com.grandis.nova.common.outbox.support.TestOutbox;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** 보존 기간이 지난 발행 완료 행만 묶음으로 지우고, 미발행 행 · 최근 행은 남긴다. */
@OutboxIntegrationTest
class OutboxCleanerTest {

    static final Duration MINUTE = Duration.ofMinutes(1);
    static final Duration RETENTION = Duration.ofDays(7);
    /** 이 시험이 넣은 행의 표시. 표는 시험 JVM 전체가 함께 쓰므로 이 행만 치우고 센다. */
    static final String MARKER = "CLEANER_TEST";

    @Autowired
    OutboxStore store;

    @Autowired
    JdbcTemplate jdbcTemplate;

    final Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    final AtomicInteger cleaned = new AtomicInteger();
    final AtomicInteger cleanupFailed = new AtomicInteger();

    @BeforeEach
    void removeLeftovers() {
        jdbcTemplate.update("DELETE FROM it_outbox_events WHERE aggregate_type = ?", MARKER);
    }

    @Test
    void 보존_기간이_지난_발행_완료_행만_묶음을_되풀이해_모두_지운다() {
        List<Long> expired = insertExpired(5);
        Long recent = insert(now.minus(RETENTION).plus(Duration.ofHours(1)));
        Long unpublished = insert(null);

        int deleted = cleaner(store, 2).clean();

        assertThat(deleted).isEqualTo(expired.size());
        assertThat(cleaned.get()).isEqualTo(deleted);
        assertThat(existing(expired)).isEmpty();
        assertThat(existing(List.of(recent, unpublished))).containsExactlyInAnyOrder(recent, unpublished);
    }

    @Test
    void 지울_행이_없으면_지표를_남기지_않는다() {
        insert(now.minus(RETENTION).plus(Duration.ofHours(1)));

        assertThat(cleaner(store, 1000).clean()).isZero();
        assertThat(cleaned.get()).isZero();
    }

    @Test
    void 중간_묶음이_실패하면_예외를_올리고_더_지우지_않되_앞서_지운_수는_지표에_남는다() {
        OutboxStore failing = mock(OutboxStore.class);
        given(failing.deletePublishedBefore(any(), anyInt()))
                .willReturn(2)
                .willThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> cleaner(failing, 2).clean()).isInstanceOf(IllegalStateException.class);

        verify(failing, times(2)).deletePublishedBefore(any(), anyInt());
        assertThat(cleaned.get()).isEqualTo(2);
    }

    @Test
    void 예약_실행의_정리가_실패하면_실패_지표를_남기고_다음_주기에_다시_돈다() {
        OutboxStore failing = mock(OutboxStore.class);
        given(failing.deletePublishedBefore(any(), anyInt())).willThrow(new IllegalStateException("db down"));
        OutboxCleaner cleaner = cleaner(failing, 2, Duration.ofMillis(10));

        cleaner.start();
        try {
            await().atMost(Duration.ofSeconds(5)).until(() -> cleanupFailed.get() >= 2);
        } finally {
            cleaner.stop();
        }
        assertThat(cleaned.get()).isZero();
    }

    /** 인터럽트로도 끝나지 않은 묶음(돌아오지 않는 DB 호출)이 있는 채로 다시 시작해도, 이전 실행은 그 묶음 뒤 멈춘다. */
    @Test
    void 끝나지_않은_이전_실행은_다시_시작한_뒤에도_되살아나지_않는다() throws Exception {
        OutboxStore stuck = mock(OutboxStore.class);
        CountDownLatch deleting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Thread> previousRun = new AtomicReference<>();
        AtomicInteger callsByPreviousRun = new AtomicInteger();
        given(stuck.deletePublishedBefore(any(), anyInt())).willAnswer(invocation -> {
            if (previousRun.compareAndSet(null, Thread.currentThread())) {
                deleting.countDown();
                while (release.getCount() > 0) {
                    try {
                        release.await();
                    } catch (InterruptedException ignored) {
                        // 인터럽트에 반응하지 않는 호출을 흉내 낸다
                    }
                }
            } else if (Thread.currentThread() == previousRun.get()) {
                callsByPreviousRun.incrementAndGet();
            }
            return 2;
        });
        OutboxCleaner cleaner = cleaner(stuck, 2, Duration.ofMillis(10));
        cleaner.start();
        assertThat(deleting.await(5, TimeUnit.SECONDS)).isTrue();

        cleaner.stop();
        cleaner.start();
        release.countDown();
        // 꽉 찬 묶음을 돌려받은 이전 실행이 되살아났다면 곧바로 다음 묶음을 부른다. 그 스레드가 끝난 뒤에 센다
        Thread previous = previousRun.get();
        previous.join(Duration.ofSeconds(10));
        cleaner.stop();

        assertThat(previous.isAlive()).as("이전 실행 스레드가 끝났다").isFalse();
        assertThat(callsByPreviousRun.get()).isZero();
    }

    @Test
    void 두_인스턴스가_동시에_정리해도_같은_행을_두_번_지우지_않고_모두_지운다() throws Exception {
        List<Long> expired = insertExpired(20);
        Long recent = insert(now.minus(RETENTION).plus(Duration.ofHours(1)));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        List<CompletableFuture<Integer>> runs = IntStream.range(0, 2)
                .mapToObj(i -> CompletableFuture.supplyAsync(() -> {
                    ready.countDown();
                    awaitLatch(start);
                    return cleaner(store, 3).clean();
                }))
                .toList();
        // 두 작업이 모두 시작 신호를 기다릴 때 함께 출발시킨다
        awaitLatch(ready);
        start.countDown();
        int deleted = runs.stream().mapToInt(run -> run.orTimeout(30, TimeUnit.SECONDS).join()).sum();

        assertThat(deleted).isEqualTo(expired.size());
        assertThat(existing(expired)).isEmpty();
        assertThat(existing(List.of(recent))).containsExactly(recent);
    }

    @Test
    void 다시_시작해도_정리는_하나만_돌고_멈춘_뒤_다시_시작할_수_있다() {
        OutboxStore counting = mock(OutboxStore.class);
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger running = new AtomicInteger();
        AtomicInteger maxRunning = new AtomicInteger();
        given(counting.deletePublishedBefore(any(), anyInt())).willAnswer(invocation -> {
            calls.incrementAndGet();
            maxRunning.accumulateAndGet(running.incrementAndGet(), Math::max);
            Thread.sleep(20);
            running.decrementAndGet();
            return 0;
        });
        OutboxCleaner cleaner = cleaner(counting, 1000, Duration.ofMillis(1));

        cleaner.start();
        cleaner.start();
        try {
            // 실행기가 둘이면 겹치는 정리가 생길 만큼 여러 번 돌 때까지 기다린다
            await().atMost(Duration.ofSeconds(5)).until(() -> calls.get() >= 5);
        } finally {
            cleaner.stop();
        }
        assertThat(maxRunning.get()).isEqualTo(1);
        assertThat(cleaner.isRunning()).isFalse();

        int before = calls.get();
        cleaner.start();
        try {
            await().atMost(Duration.ofSeconds(5)).until(() -> calls.get() > before);
        } finally {
            cleaner.stop();
        }
        assertThat(cleaner.isRunning()).isFalse();
    }

    @Test
    void 멈출_때는_지우던_묶음만_마치고_다음_묶음을_시작하지_않는다() throws Exception {
        OutboxStore slow = mock(OutboxStore.class);
        CountDownLatch deleting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        given(slow.deletePublishedBefore(any(), anyInt())).willAnswer(invocation -> {
            deleting.countDown();
            release.await(10, TimeUnit.SECONDS);
            return 2;
        });
        OutboxCleaner cleaner = cleaner(slow, 2, Duration.ofMillis(10));
        cleaner.start();
        assertThat(deleting.await(5, TimeUnit.SECONDS)).isTrue();

        Thread stopping = Thread.ofPlatform().start(cleaner::stop);
        // 멈춤 표시를 켜고 종료를 기다리기 시작한 뒤 지우던 묶음을 끝낸다 — 꽉 찬 묶음이라도 다음 묶음은 시작하지 않아야 한다
        await().atMost(Duration.ofSeconds(5)).until(() -> stopping.getState() == Thread.State.TIMED_WAITING);
        release.countDown();
        stopping.join(Duration.ofSeconds(10));

        assertThat(stopping.isAlive()).as("stop() 이 돌아왔다").isFalse();

        verify(slow, times(1)).deletePublishedBefore(any(), anyInt());
        assertThat(cleaned.get()).isEqualTo(2);
        assertThat(cleaner.isRunning()).isFalse();
    }

    private OutboxCleaner cleaner(OutboxStore target, int batch) {
        return cleaner(target, batch, Duration.ofHours(1));
    }

    private OutboxCleaner cleaner(OutboxStore target, int batch, Duration interval) {
        OutboxProperties properties = new OutboxProperties(32, MINUTE, 100, MINUTE, Duration.ofSeconds(10), "outbox",
                RETENTION, interval, batch);
        OutboxMetrics metrics = new OutboxMetrics() {
            @Override
            public void cleaned(int count) {
                cleaned.addAndGet(count);
            }

            @Override
            public void cleanupFailed() {
                cleanupFailed.incrementAndGet();
            }
        };
        return new OutboxCleaner(target, metrics, properties, Clock.fixed(now, ZoneOffset.UTC));
    }

    private List<Long> insertExpired(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> insert(now.minus(RETENTION).minus(Duration.ofHours(1 + i))))
                .toList();
    }

    /** 만든 지는 30일 전이다. 발행 시각이 null 이면 미발행 행. DB 는 UTC 벽시계 시각을 담는다. */
    private Long insert(Instant publishedAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO it_outbox_events
                        (event_id, aggregate_type, aggregate_id, event_type, payload, created_at, published_at)
                    VALUES (?, ?, UNHEX(REPLACE(UUID(), '-', '')), ?, '{}', ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, UUID.randomUUID().toString());
            statement.setString(2, MARKER);
            statement.setString(3, TestOutbox.EventType.ITEM_SETTLED.name());
            statement.setTimestamp(4, utc(now.minus(Duration.ofDays(30))));
            statement.setTimestamp(5, publishedAt == null ? null : utc(publishedAt));
            return statement;
        }, keyHolder);
        return keyHolder.getKeyAs(Number.class).longValue();
    }

    private static Timestamp utc(Instant instant) {
        return Timestamp.valueOf(instant.atOffset(ZoneOffset.UTC).toLocalDateTime());
    }

    private List<Long> existing(List<Long> ids) {
        return jdbcTemplate.queryForList("SELECT id FROM it_outbox_events WHERE id IN (%s)"
                .formatted(String.join(",", ids.stream().map(String::valueOf).toList())), Long.class);
    }

    /** 시간 안에 풀리지 않으면 실패시킨다 — 조용히 넘어가면 함께 출발한다는 전제가 깨진 채 통과한다. */
    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("10초 안에 래치가 풀리지 않았다");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("래치를 기다리다 인터럽트됐다", e);
        }
    }
}
