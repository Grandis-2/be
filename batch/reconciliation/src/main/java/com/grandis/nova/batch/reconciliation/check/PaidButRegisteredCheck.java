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
 * preorder 수신 DLQ 에 쌓였거나 발행이 멈추면 생긴다. 결제 확인 이벤트가 오는 동안은 처리 중이라, 결제가 멈춤 경계(시작 − 45분)
 * 전에 성공한 것만 본다 — 그때까지 반영되지 않았으면 재전달을 다 써서 수신 DLQ 에 갔거나 멈춘 것이다. 예약 쪽 변경 시각은 보지 않는다:
 * 결제는 결제 가능에서만 하므로 결제 뒤에 새로 결제 가능이 되는 흐름이 없고, 메모 변경처럼 상태가 그대로인 수정이 시각만 바꾼다.
 * 취소 도중 결제가 확인된 예약은 취소 중이라 여기 걸리지 않는다. 같은 서비스 안에서 한 트랜잭션으로 바뀌는 재고 · 환불은 보지 않는다.
 */
@Component
class PaidButRegisteredCheck implements InternalCheck {

    private static final String SQL = """
            SELECT p.preorder_token, BIN_TO_UUID(p.product_id) AS product_id, BIN_TO_UUID(o.id) AS order_id,
                   BIN_TO_UUID(pay.id) AS payment_id,
                   pay.approved_at, p.updated_at
              FROM payments pay
              JOIN orders o ON pay.target_type = 'ORDER' AND o.id = pay.target_id
              JOIN preorders p ON p.id = o.preorder_id
             WHERE pay.status = 'SUCCEEDED' AND p.status = 'REGISTERED'
               AND pay.updated_at <= :cutoff
            """;

    private final StreamingJdbc jdbc;

    PaidButRegisteredCheck(StreamingJdbc jdbc) {
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
            findings.add(CheckCode.PAID_BUT_REGISTERED, rs.getString("preorder_token"), rs.getString("product_id"),
                    new PaidOrder(rs.getString("order_id"), rs.getString("payment_id"),
                            BeSnapshot.utc(rs, "approved_at"), BeSnapshot.utc(rs, "updated_at")), null);
        });
    }

    /** 결과에 남기는 값 — 결제한 주문과 결제, 결제 승인 시각, 예약이 마지막으로 바뀐 시각 */
    record PaidOrder(String orderId, String paymentId, Instant approvedAt, Instant preorderUpdatedAt) {
    }
}
