package com.grandis.nova.preorder.preorder.domain;

import com.grandis.nova.common.UuidBinary;
import com.grandis.nova.preorder.preorder.PreorderStatus;
import com.grandis.nova.preorder.preorder.StageCondition;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 회원 목록의 화면 단계 조건과 단계별 건수. 단계는 등록 작업 상태를 보므로 관리자 검색처럼 SQL 로 고르고, 예약 값은 id 로 다시 읽는다.
 * 단계 조건이 없는 회원 목록은 {@link CustomerPreorderListQuery} 가 읽는다.
 */
@Component
public class CustomerStageQuery {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    CustomerStageQuery(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 최신순(created_at · id 내림차순)으로 커서 자리 다음부터 최대 limit 건의 id.
     *
     * @param anyOf 이 단계들 중 하나. 비어 있으면 안 된다
     */
    public List<UUID> findIds(UUID customerId, PreorderStatus status, UUID productId, List<StageCondition> anyOf,
                              Instant afterCreatedAt, UUID afterId, int limit) {
        if (anyOf.isEmpty()) {
            throw new IllegalArgumentException("단계 조건이 비었다");
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("customerId", UuidBinary.toBytes(customerId))
                .addValue("limit", limit);
        List<String> conditions = new ArrayList<>();
        conditions.add("p.customer_id = :customerId");
        if (status != null) {
            conditions.add("p.status = :status");
            params.addValue("status", status.name());
        }
        if (productId != null) {
            conditions.add("p.product_id = :productId");
            params.addValue("productId", UuidBinary.toBytes(productId));
        }
        if (afterCreatedAt != null) {
            conditions.add("(p.created_at < :afterCreatedAt OR (p.created_at = :afterCreatedAt AND p.id < :afterId))");
            params.addValue("afterCreatedAt", ProgressSql.utc(afterCreatedAt));
            params.addValue("afterId", UuidBinary.toBytes(afterId));
        }
        List<String> stages = new ArrayList<>();
        for (int i = 0; i < anyOf.size(); i++) {
            stages.add(ProgressSql.stage(anyOf.get(i), params, "_" + i));
        }
        conditions.add("(" + String.join(" OR ", stages) + ")");
        String sql = "SELECT p.id FROM preorders p WHERE " + String.join(" AND ", conditions)
                + " ORDER BY p.created_at DESC, p.id DESC LIMIT :limit";
        return jdbcTemplate.query(sql, params, (rs, rowNum) -> UuidBinary.fromBytes(rs.getBytes(1)));
    }

    /** 그 회원의 예약 중 단계마다 맞는 수. 받은 단계 순서대로 준다. 문장 하나다. */
    public List<Long> countEach(UUID customerId, List<StageCondition> stages) {
        if (stages.isEmpty()) {
            return List.of();
        }
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("customerId", UuidBinary.toBytes(customerId));
        List<String> sums = new ArrayList<>();
        for (int i = 0; i < stages.size(); i++) {
            sums.add("COALESCE(SUM(CASE WHEN " + ProgressSql.stage(stages.get(i), params, "_" + i) + " THEN 1 ELSE 0 END), 0)");
        }
        String sql = "SELECT " + String.join(", ", sums) + " FROM preorders p WHERE p.customer_id = :customerId";
        return jdbcTemplate.queryForObject(sql, params, (rs, rowNum) -> {
            List<Long> counts = new ArrayList<>();
            for (int i = 1; i <= stages.size(); i++) {
                counts.add(rs.getLong(i));
            }
            return counts;
        });
    }
}
