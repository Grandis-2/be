package com.grandis.nova.preorder.preorder.domain;

import com.grandis.nova.common.UuidBinary;
import com.grandis.nova.preorder.preorder.AdminPreorderSearch;
import com.grandis.nova.preorder.preorder.AdminPreorderSort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 관리자 검색의 대상 id 와 총 건수. 등록 작업 상태 · 배송 차수 조건 때문에 다른 모듈의 표를 읽기 전용으로 보므로
 * 엔티티 대신 SQL 로 고르고, 예약 값은 id 로 다시 읽는다.
 */
@Component
public class AdminPreorderSearchQuery {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    AdminPreorderSearchQuery(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 그 순서로 한 페이지의 id. */
    public List<UUID> findIds(AdminPreorderSearch search, AdminPreorderSort sort, int page, int size) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("limit", size)
                .addValue("offset", (long) page * size);
        String sql = "SELECT p.id FROM preorders p" + where(search, params)
                + " ORDER BY " + orderBy(sort) + " LIMIT :limit OFFSET :offset";
        return jdbcTemplate.query(sql, params, (rs, rowNum) -> UuidBinary.fromBytes(rs.getBytes(1)));
    }

    private static String orderBy(AdminPreorderSort sort) {
        return switch (sort) {
            case NEWEST -> "p.created_at DESC, p.id DESC";
            case OLDEST -> "p.created_at ASC, p.id ASC";
            case QUEUE_POSITION -> "p.queue_position ASC, p.id ASC";
            // 결제 기한(결제 가능 시작 + 24시간)은 REGISTERED 에만 있다. 나머지는 기한 없이 뒤에 둔다
            case PAYMENT_DUE -> "CASE WHEN p.status = 'REGISTERED' THEN 0 ELSE 1 END,"
                    + " CASE WHEN p.status = 'REGISTERED' THEN p.payable_from END ASC, p.id ASC";
        };
    }

    public long count(AdminPreorderSearch search) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM preorders p" + where(search, params),
                params, Long.class);
        return total == null ? 0 : total;
    }

    private String where(AdminPreorderSearch search, MapSqlParameterSource params) {
        List<String> conditions = new ArrayList<>();
        if (search.status() != null) {
            conditions.add("p.status = :status");
            params.addValue("status", search.status().name());
        }
        if (search.customerId() != null) {
            conditions.add("p.customer_id = :customerId");
            params.addValue("customerId", UuidBinary.toBytes(search.customerId()));
        }
        if (search.productId() != null) {
            conditions.add("p.product_id = :productId");
            params.addValue("productId", UuidBinary.toBytes(search.productId()));
        }
        if (search.from() != null) {
            conditions.add("p.created_at >= :from");
            params.addValue("from", ProgressSql.utc(search.from()));
        }
        if (search.to() != null) {
            conditions.add("p.created_at < :to");
            params.addValue("to", ProgressSql.utc(search.to()));
        }
        if (search.registerJobStatus() != null) {
            conditions.add("""
                    EXISTS (SELECT 1 FROM preorder_sync_jobs j
                             WHERE j.preorder_id = p.id AND j.job_type = 'REGISTER' AND j.status = :jobStatus)""");
            params.addValue("jobStatus", search.registerJobStatus());
        }
        if (search.externalReference() != null) {
            conditions.add("p.external_reference = :externalReference");
            params.addValue("externalReference", search.externalReference());
        }
        if (search.optionId() != null) {
            conditions.add("p.option_id = :optionId");
            params.addValue("optionId", UuidBinary.toBytes(search.optionId()));
        }
        if (search.batchNumber() != null) {
            // 차수 번호는 상품 안에서만 유일하다. 그 상품에 없는 번호면 아무것도 고르지 않는다
            conditions.add("""
                    p.shipment_batch_id = (SELECT b.id FROM shipment_batches b
                                            WHERE b.product_id = :productId AND b.batch_number = :batchNumber)""");
            params.addValue("batchNumber", search.batchNumber());
        }
        if (search.progress() != null) {
            conditions.addAll(ProgressSql.progress(search.progress(), params, ""));
        }
        return conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
    }
}
