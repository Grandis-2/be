package com.grandis.nova.preorder.syncjob;

import com.grandis.nova.preorder.preorder.PreorderStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/** 일괄 재처리 후보를 id 와 판정 값만으로 읽는다. 작업 · 예약 · 마지막 시도를 한 쿼리로 붙이고 건수를 제한한다. */
@Component
class ReprocessCandidateReader {

    private static final String SELECT = """
            SELECT j.id, j.job_type, j.status, p.status, a.error_code
              FROM preorder_sync_jobs j
              JOIN preorders p ON p.id = j.preorder_id
            """ + SyncAttemptReader.LATEST_ATTEMPT_JOIN;

    private static final RowMapper<ReprocessCandidate> CANDIDATE = (rs, rowNum) -> new ReprocessCandidate(
            rs.getLong(1), SyncJobType.valueOf(rs.getString(2)), SyncJobStatus.valueOf(rs.getString(3)),
            PreorderStatus.valueOf(rs.getString(4)), rs.getString(5));

    private final JdbcTemplate jdbcTemplate;

    ReprocessCandidateReader(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 재처리할 수 있는 DEAD_LETTER REGISTER(예약이 취소 중 · 취소가 아닌 것)를 id 순으로 최대 limit 건.
     * 조건을 쿼리에 넣어 재처리 불가인 작업이 상한을 차지하지 않게 한다. errorCode 가 있으면 마지막 시도가 그 값인 것만.
     */
    public List<ReprocessCandidate> findDeadLetters(String errorCode, int limit) {
        List<Object> args = new ArrayList<>(List.of(SyncJobType.REGISTER.name(), SyncJobStatus.DEAD_LETTER.name(),
                PreorderStatus.CANCELING.name(), PreorderStatus.CANCELED.name()));
        String errorCondition = "";
        if (errorCode != null) {
            errorCondition = " AND a.error_code = ?";
            args.add(errorCode);
        }
        args.add(limit);
        return jdbcTemplate.query(SELECT + " WHERE j.job_type = ? AND j.status = ? AND p.status NOT IN (?, ?)"
                + errorCondition
                + " ORDER BY j.id LIMIT ?", CANDIDATE, args.toArray());
    }

    /** 지정한 작업들. 없는 id 는 결과에 없다. */
    public List<ReprocessCandidate> findByIds(Collection<Long> syncJobIds) {
        if (syncJobIds.isEmpty()) {
            return List.of();
        }
        String placeholders = syncJobIds.stream().map(id -> "?").collect(Collectors.joining(", "));
        return jdbcTemplate.query(SELECT + " WHERE j.id IN (" + placeholders + ") ORDER BY j.id", CANDIDATE,
                syncJobIds.toArray());
    }
}
