package com.grandis.nova.payment.refund;

import com.grandis.nova.common.UuidBinary;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * 환불이 멈췄을 수 있는 주문을 고른다: 성공 결제가 그대로인데 마지막 환불이 확정 실패 · 에스컬레이션이거나, 진행 중인데 오래 열려 있다.
 * 주문 대상만이다 — 응모는 환불하지 않는다. 마지막 환불은 만든 순(created_at, 같으면 id)이다 — 원장의 판정과 같은 순서다.
 * 고르기만 하고 판정은 {@link RefundIssue} 가 한다. 멈춘 환불은 드물어 상한까지 한 번에 읽는다.
 */
@Component
class RefundIssueQuery {

    private static final String SQL = """
            SELECT t.target_id FROM payment_transactions t
              JOIN payments p ON p.target_type = t.target_type AND p.target_id = t.target_id
             WHERE t.transaction_type = 'REFUND' AND t.target_type = 'ORDER' AND p.status = 'SUCCEEDED'
               AND (t.status = 'FAILED' OR t.escalated_at IS NOT NULL
                    OR (t.status IN ('PENDING', 'PROCESSING', 'RETRY_SCHEDULED') AND t.created_at < :staleBefore))
               AND NOT EXISTS (SELECT 1 FROM payment_transactions n
                                WHERE n.target_type = t.target_type AND n.target_id = t.target_id
                                  AND n.transaction_type = 'REFUND'
                                  AND (n.created_at > t.created_at OR (n.created_at = t.created_at AND n.id > t.id)))
             ORDER BY t.created_at, t.id
             LIMIT :limit
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    RefundIssueQuery(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    List<UUID> stuckOrderIds(Instant staleBefore, int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("staleBefore", LocalDateTime.ofInstant(staleBefore.truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC))
                .addValue("limit", limit);
        return jdbcTemplate.query(SQL, params, (rs, rowNum) -> UuidBinary.fromBytes(rs.getBytes(1)));
    }
}
