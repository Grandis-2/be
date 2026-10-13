package com.grandis.nova.batch.reconciliation.check;

import com.grandis.nova.batch.reconciliation.snapshot.BeSnapshot;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * PAID_BUT_REGISTERED — 사전예약 주문의 결제가 성공(환불 전)했는데 예약이 결제 가능(REGISTERED)에 머문 것.
 * 결제는 order, 예약은 preorder 라 order 가 보낸 결제 확인 이벤트를 preorder 가 받아야 결제 완료(RESERVED)가 된다 — 그 이벤트가
 * preorder 수신 DLQ 에 쌓였거나 발행이 멈추면 생긴다. 결제 · 예약이 모두 기준 시각 전에 바뀐 것만 본다(그 뒤는 처리 중일 수 있다).
 * 취소 도중 결제가 확인된 예약은 취소 중이라 여기 걸리지 않는다. 같은 서비스 안에서 한 트랜잭션으로 바뀌는 재고 · 환불은 보지 않는다.
 */
@Component
class PaidButRegisteredCheck implements InternalCheck {

    private static final String SQL = """
            SELECT p.preorder_token, BIN_TO_UUID(o.id) AS order_id, BIN_TO_UUID(pay.id) AS payment_id,
                   pay.approved_at, p.updated_at
              FROM payments pay
              JOIN orders o ON pay.target_type = 'ORDER' AND o.id = pay.target_id
              JOIN preorders p ON p.id = o.preorder_id
             WHERE pay.status = 'SUCCEEDED' AND p.status = 'REGISTERED'
               AND pay.updated_at <= :cutoff AND p.updated_at <= :cutoff
            """;

    private final JdbcClient jdbc;

    PaidButRegisteredCheck(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void inspect(UUID productId, Instant cutoff, FindingSink findings) {
        JdbcClient.StatementSpec statement = jdbc
                .sql(SQL + (productId == null ? "" : " AND p.product_id = UUID_TO_BIN(:productId)"))
                .param("cutoff", LocalDateTime.ofInstant(cutoff, ZoneOffset.UTC));
        if (productId != null) {
            statement = statement.param("productId", productId.toString());
        }
        statement.query(rs -> {
            findings.add(CheckCode.PAID_BUT_REGISTERED, rs.getString("preorder_token"),
                    new PaidOrder(rs.getString("order_id"), rs.getString("payment_id"),
                            BeSnapshot.utc(rs, "approved_at"), BeSnapshot.utc(rs, "updated_at")), null);
        });
    }

    /** 결과에 남기는 값 — 결제한 주문과 결제, 결제 승인 시각, 예약이 마지막으로 바뀐 시각 */
    record PaidOrder(String orderId, String paymentId, Instant approvedAt, Instant preorderUpdatedAt) {
    }
}
