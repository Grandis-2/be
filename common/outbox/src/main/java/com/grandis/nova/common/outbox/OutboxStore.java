package com.grandis.nova.common.outbox;

import com.grandis.nova.common.UuidBinary;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 서비스 아웃박스 표의 읽기 · 쓰기. 표 이름을 모르는 공통 코드라 엔티티 대신 JDBC 로 쓴다.
 * 업무 트랜잭션 안에서 부르면 그 트랜잭션의 커넥션을 쓴다(JPA 트랜잭션도 같다).
 *
 * 시각은 UTC LocalDateTime 으로 바인딩 · 조회해 JDBC URL 의 시간대 설정에 기대지 않고, 칸 해상도(마이크로초)로 내려 적는다 —
 * 리스 값을 같음으로 비교하므로 적은 값과 들고 있는 값이 같아야 한다.
 * ddl-auto: validate 가 이 표를 보지 않으므로 기동할 때 칸이 모두 있는지 확인한다.
 */
class OutboxStore implements InitializingBean {

    private static final String COLUMNS = "id, event_id, aggregate_type, aggregate_id, event_type, payload, "
            + "publish_attempts, created_at, published_at";

    private final JdbcClient jdbc;
    private final String table;
    private final RowMapper<OutboxRow> rowMapper = OutboxStore::toRow;

    OutboxStore(DataSource dataSource, OutboxDefinition definition) {
        this.jdbc = JdbcClient.create(dataSource);
        this.table = definition.table();
    }

    /** 표 · 칸이 없으면 기동을 멈춘다. 형 · 길이는 보지 않는다. */
    @Override
    public void afterPropertiesSet() {
        try {
            jdbc.sql("SELECT " + COLUMNS + ", lease_until FROM " + table + " WHERE 1 = 0").query().listOfRows();
        } catch (DataAccessException e) {
            throw new IllegalStateException("아웃박스 표 " + table + " 를 읽지 못했다 — 마이그레이션과 OutboxDefinition 의 표 이름을 확인한다", e);
        }
    }

    Long insert(String eventId, String aggregateType, UUID aggregateId, String eventType, String payload,
                Instant createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO " + table
                        + " (event_id, aggregate_type, aggregate_id, event_type, payload, created_at)"
                        + " VALUES (:eventId, :aggregateType, :aggregateId, :eventType, :payload, :createdAt)")
                .param("eventId", eventId)
                .param("aggregateType", aggregateType)
                .param("aggregateId", UuidBinary.toBytes(aggregateId))
                .param("eventType", eventType)
                .param("payload", payload)
                .param("createdAt", utc(createdAt))
                .update(keyHolder, "id");
        return keyHolder.getKeyAs(Number.class).longValue();
    }

    Optional<OutboxRow> find(Long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM " + table + " WHERE id = :id")
                .param("id", id)
                .query(rowMapper)
                .optional();
    }

    /** 발행 완료 표시. 이미 채워졌으면 바꾸지 않는다. */
    /**
     * 발행이 끝나고 cutoff 보다 먼저 발행한 행을 limit 건까지 지운다. 미발행 행은 건드리지 않는다.
     * 정렬을 미발행 인덱스(published_at, publish_attempts, id) 순서와 맞춰 그 범위만 읽는다 — id 순이면 기본 키를 훑어
     * 지울 행이 적은 마지막 묶음이 표 전체를 읽는다. 한 번에 지우는 양을 묶어 잠금 · 복제 지연을 짧게 둔다.
     *
     * @return 지운 행 수. limit 보다 작으면 더 지울 행이 없다
     */
    int deletePublishedBefore(Instant cutoff, int limit) {
        return jdbc.sql("DELETE FROM " + table + " WHERE published_at IS NOT NULL AND published_at < :cutoff"
                        + " ORDER BY published_at, publish_attempts, id LIMIT :limit")
                .param("cutoff", utc(cutoff))
                .param("limit", limit)
                .update();
    }

    int markPublished(Long id, Instant now) {
        return jdbc.sql("UPDATE " + table + " SET published_at = :now WHERE id = :id AND published_at IS NULL")
                .param("now", utc(now))
                .param("id", id)
                .update();
    }

    /**
     * 발행 실패 횟수를 올리고, 이 리스(lease)가 아직 걸려 있으면 풀어 다음 릴레이 주기에 다시 가져가게 한다.
     * 리스 값이 소유 표식이다 — 리스가 끝나 다른 인스턴스가 새로 건 리스는 풀지 않는다. 리스 없이 보낸 경우(커밋 직후 발행)는 null.
     */
    int recordFailure(Long id, Instant lease) {
        return jdbc.sql("UPDATE " + table + " SET publish_attempts = publish_attempts + 1,"
                        + " lease_until = CASE WHEN lease_until = :lease THEN NULL ELSE lease_until END"
                        + " WHERE id = :id")
                .param("lease", lease == null ? null : utc(lease))
                .param("id", id)
                .update();
    }

    /**
     * 릴레이가 가져갈 행을 잠근다. 리스가 없거나 끝난 행만, 다른 인스턴스가 잠근 행은 건너뛴다(SKIP LOCKED).
     * 잠금은 같은 트랜잭션에서 {@link #lease} 로 리스를 건 뒤 커밋하면서 바로 푼다.
     * 실패가 적은 행부터 가져간다 — 늘 실패하는 행(없는 큐 등)이 묶음 앞자리를 차지해 다른 행을 막지 않게.
     */
    List<OutboxRow> lockClaimable(Instant createdBefore, Instant now, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM " + table
                        + " WHERE published_at IS NULL AND created_at <= :createdBefore"
                        + "   AND (lease_until IS NULL OR lease_until <= :now)"
                        + " ORDER BY publish_attempts, id LIMIT :limit FOR UPDATE SKIP LOCKED")
                .param("createdBefore", utc(createdBefore))
                .param("now", utc(now))
                .param("limit", limit)
                .query(rowMapper)
                .list();
    }

    /** 잠근 행에 리스를 건다. */
    int lease(Collection<Long> ids, Instant until) {
        return jdbc.sql("UPDATE " + table + " SET lease_until = :until WHERE id IN (:ids)")
                .param("until", utc(until))
                .param("ids", ids)
                .update();
    }

    /**
     * 보내기 직전에 리스를 연장한다. 아직 내 리스(lease)이고 미발행일 때만 바꾼다 —
     * 0 이면 리스가 끝나 다른 인스턴스가 가져갔거나 이미 발행된 행이라 보내지 않는다.
     */
    int renewLease(Long id, Instant lease, Instant renewed) {
        return jdbc.sql("UPDATE " + table + " SET lease_until = :renewed"
                        + " WHERE id = :id AND lease_until = :lease AND published_at IS NULL")
                .param("renewed", utc(renewed))
                .param("id", id)
                .param("lease", utc(lease))
                .update();
    }

    /** 가져갔지만 보내지 않을 행의 리스를 푼다. 아직 내 리스이고 미발행인 행만 — 다음 릴레이가 곧바로 다시 가져간다. */
    int releaseLeases(Collection<Long> ids, Instant lease) {
        return jdbc.sql("UPDATE " + table + " SET lease_until = NULL"
                        + " WHERE id IN (:ids) AND lease_until = :lease AND published_at IS NULL")
                .param("ids", ids)
                .param("lease", utc(lease))
                .update();
    }

    /** 아직 보내지 못한 행 수. 늘어나면 전송이 막힌 것이다. */
    long countUnpublished() {
        return jdbc.sql("SELECT COUNT(*) FROM " + table + " WHERE published_at IS NULL").query(Long.class).single();
    }

    /** 미발행 행 가운데 가장 많이 실패한 횟수. 한 행만 계속 실패하는 것(독이 든 메시지)을 드러낸다. */
    int maxUnpublishedAttempts() {
        return jdbc.sql("SELECT COALESCE(MAX(publish_attempts), 0) FROM " + table + " WHERE published_at IS NULL")
                .query(Integer.class).single();
    }

    static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant.truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        LocalDateTime value = rs.getObject(column, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private static OutboxRow toRow(ResultSet rs, int rowNum) throws SQLException {
        return new OutboxRow(rs.getLong("id"), rs.getString("event_id"), rs.getString("aggregate_type"),
                UuidBinary.fromBytes(rs.getBytes("aggregate_id")), rs.getString("event_type"), rs.getString("payload"),
                rs.getInt("publish_attempts"), instant(rs, "created_at"), instant(rs, "published_at"));
    }
}
