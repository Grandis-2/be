package com.grandis.nova.batch.reconciliation.check;

import com.grandis.nova.batch.reconciliation.snapshot.BeSnapshot;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * STUCK 가운데 예약 표 밖의 것 — 기준 시각 전에 생겼는데 아직 발행되지 않은 preorder 이벤트, 기준 시각 전에 시작했는데 결과가
 * 없는 외부 호출 차례. 멈춘 건을 다시 보내는 작업이 없고 SQS 보존 기간이 지나면 재전달도 끊겨 이 검사만 찾을 수 있다.
 * 기준 시각(시작 − 45분)이 멈춤 기준 시간과 같다. 오래 멈춘 등록 대기 · 취소 중 예약은 {@link PreorderRowCheck} 가 본다.
 *
 * 대상 키는 그 행이 가리키는 예약의 외부 키다(관리자 예약 상세로 연다). 회차 이벤트는 예약이 없어 상품 id 다.
 * 같은 예약이 이미 STUCK 이면 한 건으로 남는다.
 */
@Component
class StuckRowsCheck implements InternalCheck {

    // 이벤트의 대상(aggregate)은 등록 · 취소 작업, 예약, 회차(상품) 셋이다. 미발행 행은 ix_preorder_outbox_unpublished 로 찾는다
    private static final String UNPUBLISHED = """
            SELECT e.event_id, e.event_type, e.publish_attempts, e.created_at,
                   COALESCE(jp.preorder_token, pp.preorder_token, BIN_TO_UUID(e.aggregate_id)) AS target_key
              FROM preorder_outbox_events e
              LEFT JOIN preorder_sync_jobs j ON e.aggregate_type = 'PREORDER_SYNC_JOB' AND j.id = e.aggregate_id
              LEFT JOIN preorders jp ON jp.id = j.preorder_id
              LEFT JOIN preorders pp ON e.aggregate_type = 'PREORDER' AND pp.id = e.aggregate_id
             WHERE e.published_at IS NULL AND e.created_at <= :cutoff
            """;

    private static final String UNPUBLISHED_IN_PRODUCT = """
               AND COALESCE(jp.product_id, pp.product_id,
                            CASE WHEN e.aggregate_type = 'PREORDER_CAMPAIGN' THEN e.aggregate_id END)
                   = UUID_TO_BIN(:productId)
            """;

    private static final String UNFINISHED_ATTEMPTS = """
            SELECT p.preorder_token, j.job_type, a.attempt_number, a.started_at
              FROM preorder_sync_attempts a
              JOIN preorder_sync_jobs j ON j.id = a.sync_job_id
              JOIN preorders p ON p.id = j.preorder_id
             WHERE a.result IS NULL AND a.started_at <= :cutoff
            """;

    private final StreamingJdbc jdbc;

    StuckRowsCheck(StreamingJdbc jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void inspect(UUID productId, Instant cutoff, FindingSink findings) {
        LocalDateTime at = LocalDateTime.ofInstant(cutoff, ZoneOffset.UTC);
        statement(UNPUBLISHED + (productId == null ? "" : UNPUBLISHED_IN_PRODUCT), at, productId)
                .query(rs -> {
                    findings.add(CheckCode.STUCK, rs.getString("target_key"),
                            new UnpublishedEvent(rs.getString("event_id"), rs.getString("event_type"),
                                    rs.getInt("publish_attempts"), BeSnapshot.utc(rs, "created_at")), null);
                });
        statement(UNFINISHED_ATTEMPTS + (productId == null ? "" : " AND p.product_id = UUID_TO_BIN(:productId)"),
                at, productId)
                .query(rs -> {
                    findings.add(CheckCode.STUCK, rs.getString("preorder_token"),
                            new UnfinishedAttempt(rs.getString("job_type"), rs.getInt("attempt_number"),
                                    BeSnapshot.utc(rs, "started_at")), null);
                });
    }

    private JdbcClient.StatementSpec statement(String sql, LocalDateTime cutoff, UUID productId) {
        JdbcClient.StatementSpec statement = jdbc.sql(sql).param("cutoff", cutoff);
        return productId == null ? statement : statement.param("productId", productId.toString());
    }

    /** 결과에 남기는 값 — 발행되지 않은 이벤트 */
    record UnpublishedEvent(String eventId, String eventType, int publishAttempts, Instant createdAt) {
    }

    /** 결과에 남기는 값 — 결과 없는 외부 호출 차례 */
    record UnfinishedAttempt(String jobType, int attemptNumber, Instant startedAt) {
    }
}
