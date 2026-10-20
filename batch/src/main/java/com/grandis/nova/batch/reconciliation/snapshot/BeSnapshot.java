package com.grandis.nova.batch.reconciliation.snapshot;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * be 예약을 외부 키(preorder_token) 순서로 청크씩 읽는다. Mock 원장도 같은 키 순서라 둘을 메모리에 다 올리지 않고 맞댈 수 있다.
 * 두 쪽 모두 utf8mb4_bin 이고 키가 ASCII 라 DB 의 정렬과 자바 String 비교가 같다.
 *
 * 검사 트랜잭션(REPEATABLE READ · 읽기 전용) 안에서 불러야 한다. 첫 청크를 읽는 순간의 스냅샷으로 모든 청크 · be 내부 검사가 같은
 * 시점을 본다 — 그래서 Mock 을 부르기 전에 첫 청크를 먼저 읽는다(be 를 먼저 읽는다는 규칙). 청크는 키 다음부터 읽어(LIMIT) 오프셋이 없다.
 * 시각은 DB 에 UTC 로 저장돼 있어 LocalDateTime 으로 받아 UTC 로 읽는다(드라이버의 시간대 변환을 거치지 않게).
 */
@Component
public class BeSnapshot {

    // 예약당 등록 · 취소 작업은 하나씩이다(uq_sync_job_type). 마지막 이력은 예약 행의 이력 번호(event_sequence)와 같은 번호의
    // 이력이다 — preorder 가 상태를 바꿀 때 번호를 올리고 그 번호로 이력을 남긴다. 모두 기본 키 · 유일 키로 붙어 행이 늘지 않는다
    private static final String SELECT = """
            SELECT p.preorder_token, BIN_TO_UUID(p.id) AS id, p.status, p.external_reference,
                   BIN_TO_UUID(p.customer_id) AS customer_id, BIN_TO_UUID(p.product_id) AS product_id, o.sku,
                   rj.status AS register_job_status, cj.status AS cancel_job_status, e.to_status AS last_event_status,
                   p.queue_position, b.position_from AS batch_from, b.position_to AS batch_to,
                   GREATEST(p.updated_at, COALESCE(rj.updated_at, p.updated_at), COALESCE(cj.updated_at, p.updated_at))
                       AS changed_at
              FROM preorders p
              JOIN product_options o ON o.id = p.option_id
              JOIN shipment_batches b ON b.id = p.shipment_batch_id
              LEFT JOIN preorder_sync_jobs rj ON rj.preorder_id = p.id AND rj.job_type = 'REGISTER'
              LEFT JOIN preorder_sync_jobs cj ON cj.preorder_id = p.id AND cj.job_type = 'CANCEL'
              LEFT JOIN preorder_events e ON e.preorder_id = p.id AND e.event_sequence = p.event_sequence
             WHERE p.preorder_token > :after
            """;

    private final JdbcClient jdbc;

    BeSnapshot(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param productId 검사 범위. null 이면 전체
     * @param chunkSize 한 번에 읽는 건수
     */
    public Iterator<BePreorder> preorders(UUID productId, int chunkSize) {
        return new Chunks(productId, chunkSize);
    }

    /** 상품 구분 없이 그 외부 키의 예약이 있는가. 상품 범위 검사에서 Mock 에만 있는 듯한 키를 한 번 더 확인한다(드물다) */
    public boolean exists(String token) {
        return Boolean.TRUE.equals(jdbc.sql("SELECT EXISTS (SELECT 1 FROM preorders WHERE preorder_token = :token)")
                .param("token", token)
                .query(Boolean.class)
                .single());
    }

    List<BePreorder> chunk(UUID productId, String after, int size) {
        String sql = SELECT + (productId == null ? "" : " AND p.product_id = UUID_TO_BIN(:productId)")
                + " ORDER BY p.preorder_token LIMIT :size";
        JdbcClient.StatementSpec statement = jdbc.sql(sql).param("after", after).param("size", size);
        if (productId != null) {
            statement = statement.param("productId", productId.toString());
        }
        return statement.query(BeSnapshot::preorder).list();
    }

    private static BePreorder preorder(ResultSet rs, int row) throws SQLException {
        return new BePreorder(rs.getString("preorder_token"), rs.getString("id"), rs.getString("status"),
                rs.getString("external_reference"), rs.getString("customer_id"), rs.getString("product_id"),
                rs.getString("sku"), rs.getString("register_job_status"), rs.getString("cancel_job_status"),
                rs.getString("last_event_status"), rs.getLong("queue_position"), rs.getLong("batch_from"),
                rs.getObject("batch_to", Long.class), utc(rs, "changed_at"));
    }

    public static Instant utc(ResultSet rs, String column) throws SQLException {
        LocalDateTime value = rs.getObject(column, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private final class Chunks implements Iterator<BePreorder> {

        private final UUID productId;
        private final int size;
        private Iterator<BePreorder> current;
        private String after = "";
        private boolean last;

        Chunks(UUID productId, int size) {
            this.productId = productId;
            this.size = size;
        }

        @Override
        public boolean hasNext() {
            while ((current == null || !current.hasNext()) && !last) {
                List<BePreorder> rows = chunk(productId, after, size);
                current = rows.iterator();
                last = rows.size() < size;
                if (!rows.isEmpty()) {
                    after = rows.getLast().token();
                }
            }
            return current.hasNext();
        }

        @Override
        public BePreorder next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            return current.next();
        }
    }
}
