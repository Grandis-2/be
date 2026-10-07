package com.grandis.nova.preorder.syncjob;

import com.grandis.nova.common.UuidBinary;
import com.grandis.nova.preorder.syncjob.domain.ErrorGroup;
import com.grandis.nova.preorder.syncjob.domain.SyncJobFilter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 시도 기록 읽기. worker 소유 표라 엔티티로 매핑하지 않는다 — 매핑하면 쓰기가 가능한 자리가 생기고,
 * worker 가 칸을 늘릴 때마다 이 모듈의 스키마 검증이 같이 깨진다.
 *
 * 관리자 화면 전용이라 접수 경로에서는 부르지 않는다.
 */
@Component
public class SyncAttemptReader {

    private static final String FIND_BY_JOBS = """
            SELECT sync_job_id, attempt_number, actor, result, http_status, error_code, error_message,
                   started_at, finished_at
              FROM preorder_sync_attempts
             WHERE sync_job_id IN (%s)
             ORDER BY sync_job_id, attempt_number
            """;

    private final RowMapper<SyncAttempt> attemptMapper = (rs, rowNum) -> new SyncAttempt(
            UuidBinary.fromBytes(rs.getBytes(1)), rs.getInt(2), rs.getString(3), rs.getString(4),
            rs.getObject(5, Integer.class), rs.getString(6), rs.getString(7),
            rs.getTimestamp(8).toInstant(), instantOrNull(rs.getTimestamp(9)));

    /** 작업(j)에 마지막 시도(a) 하나를 붙이는 조인. 시도가 없는 작업은 a 가 NULL 이다. 재처리 후보도 같은 정의를 쓴다. */
    public static final String LATEST_ATTEMPT_JOIN = """
              LEFT JOIN preorder_sync_attempts a ON a.sync_job_id = j.id
                   AND a.attempt_number = (SELECT MAX(b.attempt_number) FROM preorder_sync_attempts b
                                            WHERE b.sync_job_id = j.id)
            """;

    private static final String LAST_ERROR_CODES = """
            SELECT j.id, a.error_code
              FROM preorder_sync_jobs j
            """ + LATEST_ATTEMPT_JOIN;

    private final JdbcTemplate jdbcTemplate;

    public SyncAttemptReader(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 작업별 시도 목록(번호 순). 작업이 없으면 빈 Map. */
    public Map<UUID, List<SyncAttempt>> findByJobIds(Collection<UUID> syncJobIds) {
        if (syncJobIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = syncJobIds.stream().map(id -> "?").collect(Collectors.joining(", "));
        return jdbcTemplate.query(FIND_BY_JOBS.formatted(placeholders), attemptMapper,
                        syncJobIds.stream().map(UuidBinary::toBytes).toArray()).stream()
                .collect(Collectors.groupingBy(SyncAttempt::syncJobId));
    }

    /** 조건에 맞는 작업을 마지막 시도의 errorCode 로 묶어 센다. 많은 것부터. */
    public List<ErrorGroup> countByLastErrorCode(SyncJobFilter filter) {
        List<String> conditions = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        if (filter.jobType() != null) {
            conditions.add("j.job_type = ?");
            args.add(filter.jobType().name());
        }
        if (filter.status() != null) {
            conditions.add("j.status = ?");
            args.add(filter.status().name());
        }
        if (filter.preorderId() != null) {
            conditions.add("j.preorder_id = ?");
            args.add(UuidBinary.toBytes(filter.preorderId()));
        }
        String where = conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
        String sql = "SELECT error_code, COUNT(*) FROM (" + LAST_ERROR_CODES + where + ") last"
                + " GROUP BY error_code ORDER BY COUNT(*) DESC, error_code";
        return jdbcTemplate.query(sql, (rs, rowNum) -> new ErrorGroup(rs.getString(1), rs.getLong(2)), args.toArray());
    }

    private Instant instantOrNull(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
