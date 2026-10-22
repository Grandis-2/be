package com.grandis.nova.batch.reconciliation.check;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * QUEUE_GAP — 상품마다 최대 순번 ≠ 예약 수. 순번 발급과 예약 넣기가 한 접수 트랜잭션이고 예약 행은 지우지 않아, 같지 않으면
 * 잃어버린 예약 행이 있다. 한 스냅샷이라 기준 시각 뒤에 들어온 예약도 순번과 함께 보여 판정 보류 없이 센다.
 * 대상 키는 상품 id 다. uq_preorder_position(product_id, queue_position)만 읽는다.
 */
@Component
class QueueGapCheck implements InternalCheck {

    private static final String SQL = """
            SELECT BIN_TO_UUID(product_id) AS product_id, MAX(queue_position) AS max_position, COUNT(*) AS preorders
              FROM preorders
            """;

    private static final String GROUP = " GROUP BY product_id HAVING MAX(queue_position) <> COUNT(*)";

    private final JdbcClient jdbc;

    QueueGapCheck(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void inspect(RunScope scope, FindingSink findings) {
        UUID productId = scope.productId();
        JdbcClient.StatementSpec statement = jdbc.sql(SQL
                + (productId == null ? "" : " WHERE product_id = UUID_TO_BIN(:productId)") + GROUP);
        if (productId != null) {
            statement = statement.param("productId", productId.toString());
        }
        statement.query(rs -> {
            String product = rs.getString("product_id");
            findings.add(CheckCode.QUEUE_GAP, product,
                    new PositionCount(product, rs.getLong("max_position"), rs.getLong("preorders")), null);
        });
    }

    /** 결과에 남기는 값 — 상품의 최대 순번과 예약 수 */
    record PositionCount(String productId, long maxPosition, long preorders) {
    }
}
