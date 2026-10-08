package com.grandis.nova.common.outbox;

import com.grandis.nova.common.outbox.support.OutboxIntegrationTest;
import com.grandis.nova.common.outbox.support.TestOutbox;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** 보존 기간이 지난 발행 완료 행만 묶음으로 지우고, 미발행 행 · 최근 행은 남긴다. */
@OutboxIntegrationTest
class OutboxCleanerTest {

    static final Duration MINUTE = Duration.ofMinutes(1);
    static final Duration RETENTION = Duration.ofDays(7);

    @Autowired
    OutboxStore store;

    @Autowired
    JdbcTemplate jdbcTemplate;

    final Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    final AtomicInteger cleaned = new AtomicInteger();

    @Test
    void 보존_기간이_지난_발행_완료_행만_묶음을_되풀이해_모두_지운다() {
        List<Long> expired = IntStream.range(0, 5)
                .mapToObj(i -> insert(now.minus(Duration.ofDays(30)), now.minus(RETENTION).minusSeconds(1 + i)))
                .toList();
        Long recent = insert(now.minus(Duration.ofDays(30)), now.minus(RETENTION).plusSeconds(60));
        Long unpublished = insert(now.minus(Duration.ofDays(30)), null);

        int deleted = cleaner(2).clean();

        assertThat(deleted).isGreaterThanOrEqualTo(expired.size());
        assertThat(cleaned.get()).isEqualTo(deleted);
        assertThat(existing(expired)).isEmpty();
        assertThat(existing(List.of(recent, unpublished))).containsExactlyInAnyOrder(recent, unpublished);
    }

    @Test
    void 지울_행이_없으면_지표를_남기지_않는다() {
        assertThat(cleaner(1000).clean()).isZero();
        assertThat(cleaned.get()).isZero();
    }

    @Test
    void 다시_시작해도_실행기는_하나이고_멈추면_돌지_않는다() {
        OutboxCleaner cleaner = cleaner(1000);

        cleaner.start();
        cleaner.start();
        assertThat(cleaner.isRunning()).isTrue();
        cleaner.stop();

        assertThat(cleaner.isRunning()).isFalse();
    }

    private OutboxCleaner cleaner(int batch) {
        OutboxProperties properties = new OutboxProperties(32, MINUTE, 100, MINUTE, Duration.ofSeconds(10), "outbox",
                RETENTION, Duration.ofHours(1), batch);
        OutboxMetrics metrics = new OutboxMetrics() {
            @Override
            public void cleaned(int count) {
                cleaned.addAndGet(count);
            }
        };
        return new OutboxCleaner(store, metrics, properties, Clock.fixed(now, ZoneOffset.UTC));
    }

    /** DB 는 UTC 벽시계 시각을 담는다. */
    private Long insert(Instant createdAt, Instant publishedAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO it_outbox_events
                        (event_id, aggregate_type, aggregate_id, event_type, payload, created_at, published_at)
                    VALUES (?, 'ITEM', UNHEX(REPLACE(UUID(), '-', '')), ?, '{}', ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, UUID.randomUUID().toString());
            statement.setString(2, TestOutbox.EventType.ITEM_SETTLED.name());
            statement.setTimestamp(3, Timestamp.valueOf(createdAt.atOffset(ZoneOffset.UTC).toLocalDateTime()));
            statement.setTimestamp(4, publishedAt == null ? null
                    : Timestamp.valueOf(publishedAt.atOffset(ZoneOffset.UTC).toLocalDateTime()));
            return statement;
        }, keyHolder);
        return keyHolder.getKeyAs(Number.class).longValue();
    }

    private List<Long> existing(List<Long> ids) {
        return jdbcTemplate.queryForList("SELECT id FROM it_outbox_events WHERE id IN (%s)"
                .formatted(String.join(",", ids.stream().map(String::valueOf).toList())), Long.class);
    }
}
