package com.grandis.nova.payment.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/**
 * 통합 테스트는 커밋된 행을 공유한다. 워커를 돌리는 테스트는 먼저 다른 테스트가 남긴 복구 후보를 치워 자기 후보만 남긴다.
 * 치운 행도 도메인 불변식을 지켜야 한다 — 다른 테스트가 대상별 거래를 읽을 때 매퍼가 깨지지 않게.
 */
public final class RecoveryCandidates {

    private RecoveryCandidates() {
    }

    public static void parkOthers(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.update("""
                UPDATE payment_transactions SET escalated_at = UTC_TIMESTAMP(6)
                 WHERE escalated_at IS NULL AND status IN ('PROCESSING', 'RETRY_SCHEDULED')
                """);
        // 보내지 않은 REFUND 는 에스컬레이션할 수 없다(ck_payment_tx_escalated) — 한 번 보낸 뒤 멈춘 행으로 만든다
        jdbcTemplate.update("""
                UPDATE payment_transactions
                   SET status = 'PROCESSING', attempt_count = 1, requested_at = UTC_TIMESTAMP(6),
                       lease_token = UUID(), lease_expires_at = UTC_TIMESTAMP(6), escalated_at = UTC_TIMESTAMP(6)
                 WHERE status = 'PENDING' AND transaction_type = 'REFUND'
                """);
    }
}
