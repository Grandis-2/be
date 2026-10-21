package com.grandis.nova.batch.reconciliation.check;

import com.grandis.nova.batch.reconciliation.snapshot.BeSnapshot;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * OUTBOX_MISSING 가운데 대기(PENDING) 중인 등록 작업에 그 실행 회차의 발행 이벤트(REGISTER_JOB_READY)가 없는 것.
 * 접수 · 재처리는 작업과 이벤트를 한 트랜잭션에 넣으므로, 이벤트가 없으면 worker 가 그 작업을 영영 모른다.
 * 발행이 끝난 이벤트는 보존 기간(7일) 뒤 지워지므로 대기 중인 작업만 본다. 등록 작업이 아예 없는 예약은 {@link PreorderRowCheck} 가 본다.
 *
 * 이벤트 표에는 대상 id 인덱스가 없어 작업마다 훑는다. 그래서 멈춤 경계(시작 − 45분) 전부터 대기인 작업만 본다 — 정상이면 없고,
 * 이벤트가 없는 작업은 영영 처리되지 않으므로 늦게 잡아도 놓치지 않는다.
 */
@Component
class RegisterEventCheck implements InternalCheck {

    private static final String SQL = """
            SELECT p.preorder_token, BIN_TO_UUID(j.id) AS job_id, j.run, j.updated_at
              FROM preorder_sync_jobs j
              JOIN preorders p ON p.id = j.preorder_id
             WHERE j.job_type = 'REGISTER' AND j.status = 'PENDING' AND j.updated_at <= :cutoff
               AND NOT EXISTS (SELECT 1
                                 FROM preorder_outbox_events e
                                WHERE e.aggregate_id = j.id AND e.event_type = 'REGISTER_JOB_READY'
                                  AND CAST(JSON_EXTRACT(e.payload, '$.run') AS UNSIGNED) = j.run)
            """;

    private final StreamingJdbc jdbc;

    RegisterEventCheck(StreamingJdbc jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void inspect(RunScope scope, FindingSink findings) {
        UUID productId = scope.productId();
        JdbcClient.StatementSpec statement = jdbc
                .sql(SQL + (productId == null ? "" : " AND p.product_id = UUID_TO_BIN(:productId)"))
                .param("cutoff", LocalDateTime.ofInstant(scope.stuckBefore(), ZoneOffset.UTC));
        if (productId != null) {
            statement = statement.param("productId", productId.toString());
        }
        statement.query(rs -> {
            findings.add(CheckCode.OUTBOX_MISSING, rs.getString("preorder_token"),
                    new PendingJob(rs.getString("job_id"), rs.getInt("run"), BeSnapshot.utc(rs, "updated_at")), null);
        });
    }

    /** 결과에 남기는 값 — 발행 이벤트가 없는 대기 중 등록 작업 */
    record PendingJob(String syncJobId, int run, Instant updatedAt) {
    }
}
