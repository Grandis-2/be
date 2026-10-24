package com.grandis.nova.preorder.reconciliation.domain;

import com.grandis.nova.common.OffsetPage;
import com.grandis.nova.common.UuidBinary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 불일치 건 읽기와 조치 칸 바꾸기. batch 가 넣은 행에서 preorder 는 조치 칸(action_*)만 바꾼다(허용된 쓰기).
 * 실행 · 코드로 거르는 목록과 두 실행 비교는 uq_reconciliation_finding_target(run_id, check_code, target_key)로,
 * 상품으로 거르는 목록은 ix_reconciliation_finding_product(run_id, product_id)로 읽는다.
 */
@Component
public class ReconciliationFindings {

    private static final String SELECT = """
            SELECT id, run_id, check_code, target_key, product_id, be_snapshot, mock_snapshot, first_detected_at,
                   action_status,
                   action_type, action_requested_by, action_requested_at, action_note
              FROM reconciliation_findings
            """;

    private static final RowMapper<FindingRow> FINDING = (rs, rowNum) -> new FindingRow(
            UuidBinary.fromBytes(rs.getBytes(1)), UuidBinary.fromBytes(rs.getBytes(2)), rs.getString(3), rs.getString(4),
            rs.getBytes(5) == null ? null : UuidBinary.fromBytes(rs.getBytes(5)), rs.getString(6), rs.getString(7),
            ReconciliationRuns.instant(rs, 8), ActionStatus.valueOf(rs.getString(9)),
            rs.getString(10) == null ? null : ActionType.valueOf(rs.getString(10)), rs.getString(11),
            ReconciliationRuns.instant(rs, 12), rs.getString(13));

    private static final RowMapper<FindingKey> KEY = (rs, rowNum) -> new FindingKey(rs.getString(1), rs.getString(2));

    private final JdbcTemplate jdbcTemplate;

    ReconciliationFindings(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * @param checkCode 이 코드만. null 이면 전부
     * @param productId 이 상품만. null 이면 전부
     */
    public OffsetPage<FindingRow> findPage(UUID runId, String checkCode, UUID productId, int page, int size) {
        String where = " WHERE run_id = ?" + (checkCode == null ? "" : " AND check_code = ?")
                + (productId == null ? "" : " AND product_id = ?");
        List<Object> args = new ArrayList<>(List.of(UuidBinary.toBytes(runId)));
        if (checkCode != null) {
            args.add(checkCode);
        }
        if (productId != null) {
            args.add(UuidBinary.toBytes(productId));
        }
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM reconciliation_findings" + where, Long.class,
                args.toArray());
        args.add(size);
        args.add((long) page * size);
        List<FindingRow> findings = jdbcTemplate.query(
                SELECT + where + " ORDER BY check_code, target_key LIMIT ? OFFSET ?", FINDING, args.toArray());
        return OffsetPage.of(findings, page, size, total == null ? 0 : total);
    }

    public Optional<FindingRow> find(UUID findingId) {
        return jdbcTemplate.query(SELECT + " WHERE id = ?", FINDING, (Object) UuidBinary.toBytes(findingId)).stream()
                .findFirst();
    }

    /** 행을 잠그고 읽는다 — 같은 건에 조치가 동시에 들어오면 뒤의 것이 기다렸다가 바뀐 조치 상태를 본다. 트랜잭션 안에서만 부른다 */
    public Optional<FindingRow> findForUpdate(UUID findingId) {
        return jdbcTemplate.query(SELECT + " WHERE id = ? FOR UPDATE", FINDING, (Object) UuidBinary.toBytes(findingId))
                .stream()
                .findFirst();
    }

    /**
     * 조치를 시작한 건을 REQUESTED 로 남긴다. 아직 조치하지 않은(NOT_STARTED) 건만 바꾼다.
     *
     * @return 바뀐 행 수. 0 이면 그 사이 다른 조치가 먼저 들어왔다
     */
    public int markRequested(UUID findingId, ActionType actionType, String requestedBy, Instant now) {
        LocalDateTime at = LocalDateTime.ofInstant(now.truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
        return jdbcTemplate.update("""
                        UPDATE reconciliation_findings
                           SET action_status = 'REQUESTED', action_type = ?, action_requested_by = ?,
                               action_requested_at = ?, updated_at = ?
                         WHERE id = ? AND action_status = 'NOT_STARTED'
                        """,
                actionType.name(), requestedBy, at, at, UuidBinary.toBytes(findingId));
    }

    /**
     * 키로 Mock 취소를 시작한 건을 REQUESTED 로 남긴다. 아직 조치하지 않은 건과, 앞선 키로 Mock 취소가 실패한(FAILED) 건만 바꾼다 —
     * 실패는 대개 연결 문제라 다시 누를 수 있다. 앞 실패 메모는 지운다.
     *
     * @return 바뀐 행 수. 0 이면 그 사이 다른 조치가 먼저 들어왔다
     */
    public int markExternalCancelRequested(UUID findingId, String requestedBy, Instant now) {
        LocalDateTime at = utc(now);
        return jdbcTemplate.update("""
                        UPDATE reconciliation_findings
                           SET action_status = 'REQUESTED', action_type = 'EXTERNAL_CANCEL', action_requested_by = ?,
                               action_requested_at = ?, action_note = NULL, updated_at = ?
                         WHERE id = ?
                           AND (action_status = 'NOT_STARTED'
                                OR (action_status = 'FAILED' AND action_type = 'EXTERNAL_CANCEL'))
                        """,
                requestedBy, at, at, UuidBinary.toBytes(findingId));
    }

    /**
     * 키로 Mock 취소의 결과를 남긴다(SUCCEEDED · ALREADY_RESOLVED · FAILED). 그 조치로 REQUESTED 인 건만 바꾼다.
     *
     * @param note 관리자에게 보일 메모. FAILED 면 필수(DB CHECK)
     * @return 바뀐 행 수
     */
    public int finishExternalCancel(UUID findingId, ActionStatus result, String note, Instant now) {
        return jdbcTemplate.update("""
                        UPDATE reconciliation_findings
                           SET action_status = ?, action_note = ?, updated_at = ?
                         WHERE id = ? AND action_status = 'REQUESTED' AND action_type = 'EXTERNAL_CANCEL'
                        """,
                result.name(), note, utc(now), UuidBinary.toBytes(findingId));
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant.truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
    }

    /** run 에만 있고 other 에는 없는 (코드, 대상) */
    public List<FindingKey> findOnlyIn(UUID runId, UUID otherRunId) {
        return compare(runId, otherRunId, "NOT EXISTS");
    }

    /** 두 실행 모두에 있는 (코드, 대상) */
    public List<FindingKey> findInBoth(UUID runId, UUID otherRunId) {
        return compare(runId, otherRunId, "EXISTS");
    }

    private List<FindingKey> compare(UUID runId, UUID otherRunId, String exists) {
        return jdbcTemplate.query("""
                        SELECT f.check_code, f.target_key
                          FROM reconciliation_findings f
                         WHERE f.run_id = ?
                           AND %s (SELECT 1
                                     FROM reconciliation_findings o
                                    WHERE o.run_id = ? AND o.check_code = f.check_code AND o.target_key = f.target_key)
                         ORDER BY f.check_code, f.target_key
                        """.formatted(exists),
                KEY, UuidBinary.toBytes(runId), UuidBinary.toBytes(otherRunId));
    }
}
