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
    void 다시_시작해도_실행기는_하나이고_멈춘_뒤_다시_시작할_수_있다() {
        OutboxCleaner cleaner = cleaner(store, 1000);

        cleaner.start();
        cleaner.start();
        assertThat(cleaner.isRunning()).isTrue();
        cleaner.stop();
        assertThat(cleaner.isRunning()).isFalse();

        cleaner.start();
        assertThat(cleaner.isRunning()).isTrue();
        cleaner.stop();
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

        CompletableFuture<Void> stopped = CompletableFuture.runAsync(cleaner::stop);
        // 멈춤 요청이 들어간 뒤 지우던 묶음을 끝낸다 — 꽉 찬 묶음이라도 다음 묶음은 시작하지 않아야 한다
        await().pollDelay(Duration.ofMillis(200)).until(() -> true);
        release.countDown();
        stopped.get(10, TimeUnit.SECONDS);

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
