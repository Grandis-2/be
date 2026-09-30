package com.grandis.nova.preorder.preorder.domain;

import com.grandis.nova.preorder.preorder.AdminPreorderSearch;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * 관리자 검색의 대상 id 와 총 건수. 등록 작업 상태 조건 때문에 작업 표를 읽기 전용으로 조인하므로
 * 엔티티 대신 SQL 로 고르고, 예약 값은 id 로 다시 읽는다.
 */
@Component
public class AdminPreorderSearchQuery {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    AdminPreorderSearchQuery(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 최신순(created_at · id 내림차순) 한 페이지의 id. */
    public List<Long> findIds(AdminPreorderSearch search, int page, int size) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("limit", size)
                .addValue("offset", (long) page * size);
        String sql = "SELECT p.id FROM preorders p" + where(search, params)
                + " ORDER BY p.created_at DESC, p.id DESC LIMIT :limit OFFSET :offset";
        return jdbcTemplate.queryForList(sql, params, Long.class);
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
            params.addValue("customerId", search.customerId());
        }
        if (search.productId() != null) {
            conditions.add("p.product_id = :productId");
            params.addValue("productId", search.productId());
        }
        if (search.from() != null) {
            conditions.add("p.created_at >= :from");
            params.addValue("from", Timestamp.from(search.from()));
        }
        if (search.to() != null) {
            conditions.add("p.created_at < :to");
            params.addValue("to", Timestamp.from(search.to()));
        }
        if (search.registerJobStatus() != null) {
            conditions.add("""
                    EXISTS (SELECT 1 FROM preorder_sync_jobs j
                             WHERE j.preorder_id = p.id AND j.job_type = 'REGISTER' AND j.status = :jobStatus)""");
            params.addValue("jobStatus", search.registerJobStatus());
        }
        return conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
    }
}
