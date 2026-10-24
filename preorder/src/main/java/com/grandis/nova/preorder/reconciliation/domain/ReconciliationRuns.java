package com.grandis.nova.preorder.reconciliation.domain;

import com.grandis.nova.common.OffsetPage;
import com.grandis.nova.common.UuidBinary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 검사 실행 읽기. 결과 표는 batch 소유라 엔티티를 두지 않고 SQL 로 읽는다(넣기는 {@link ReconciliationRunRequest}).
 * 최근 순은 created_at 이다 — id 는 UUID v7 이지만 같은 밀리초 안의 순서가 보장되지 않는다.
 */
@Component
public class ReconciliationRuns {

    private static final String SELECT = """
            SELECT id, requested_by, product_id, status, created_at, started_at, cutoff_at, finished_at, error_message,
                   held_count, pending_sync_count, canceling_count, dead_letter_count
              FROM reconciliation_runs
            """;

    private static final RowMapper<RunRow> RUN = (rs, rowNum) -> new RunRow(UuidBinary.fromBytes(rs.getBytes(1)),
            rs.getString(2), uuidOrNull(rs.getBytes(3)), RunStatus.valueOf(rs.getString(4)), instant(rs, 5),
            instant(rs, 6), instant(rs, 7), instant(rs, 8), rs.getString(9), intOrNull(rs, 10), intOrNull(rs, 11),
            intOrNull(rs, 12), intOrNull(rs, 13));

    private final JdbcTemplate jdbcTemplate;

    ReconciliationRuns(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 최근 실행부터(ix_reconciliation_run_created) */
    public OffsetPage<RunRow> findPage(int page, int size) {
        List<RunRow> runs = jdbcTemplate.query(SELECT + " ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?", RUN,
                size, (long) page * size);
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM reconciliation_runs", Long.class);
        return OffsetPage.of(runs, page, size, total == null ? 0 : total);
    }

    public Optional<RunRow> find(UUID runId) {
        return jdbcTemplate.query(SELECT + " WHERE id = ?", RUN, (Object) UuidBinary.toBytes(runId)).stream()
                .findFirst();
    }

    /**
     * 실행마다 상품별 불일치 건수, 많은 순. 상품을 알 수 없는 건은 null 키로 센다.
     * ix_reconciliation_finding_product(run_id, product_id)만 읽는다.
     */
    public Map<UUID, Map<UUID, Long>> countFindingsByProduct(Collection<UUID> runIds) {
        Map<UUID, Map<UUID, Long>> counts = new HashMap<>();
        if (runIds.isEmpty()) {
            return counts;
        }
        String in = String.join(", ", runIds.stream().map(id -> "?").toList());
        jdbcTemplate.query("""
                        SELECT run_id, product_id, COUNT(*) AS findings
                          FROM reconciliation_findings
                         WHERE run_id IN (%s)
                         GROUP BY run_id, product_id
                         ORDER BY findings DESC, product_id
                        """.formatted(in),
                rs -> {
                    byte[] product = rs.getBytes(2);
                    counts.computeIfAbsent(UuidBinary.fromBytes(rs.getBytes(1)), id -> new LinkedHashMap<>())
                            .put(product == null ? null : UuidBinary.fromBytes(product), rs.getLong(3));
                },
                runIds.stream().map(UuidBinary::toBytes).toArray());
        return counts;
    }

    /** 실행마다 코드별 불일치 건수. 불일치가 없는 실행은 빠진다. 코드 순서는 이름순이다 */
    public Map<UUID, Map<String, Long>> countFindings(Collection<UUID> runIds) {
        Map<UUID, Map<String, Long>> counts = new HashMap<>();
        if (runIds.isEmpty()) {
            return counts;
        }
        String in = String.join(", ", runIds.stream().map(id -> "?").toList());
        jdbcTemplate.query("""
                        SELECT run_id, check_code, COUNT(*)
                          FROM reconciliation_findings
                         WHERE run_id IN (%s)
                         GROUP BY run_id, check_code
                         ORDER BY check_code
                        """.formatted(in),
                rs -> {
                    counts.computeIfAbsent(UuidBinary.fromBytes(rs.getBytes(1)), id -> new LinkedHashMap<>())
                            .put(rs.getString(2), rs.getLong(3));
                },
                runIds.stream().map(UuidBinary::toBytes).toArray());
        return counts;
    }

    static Instant instant(ResultSet rs, int column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static UUID uuidOrNull(byte[] bytes) {
        return bytes == null ? null : UuidBinary.fromBytes(bytes);
    }

    private static Integer intOrNull(ResultSet rs, int column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }
}
