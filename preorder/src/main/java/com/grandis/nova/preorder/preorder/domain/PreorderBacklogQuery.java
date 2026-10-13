package com.grandis.nova.preorder.preorder.domain;

import com.grandis.nova.common.UuidBinary;
import com.grandis.nova.preorder.preorder.PreorderStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 운영 현황용 집계. 상품 조건이 있고 없음에 따라 타는 색인이 달라 조건을 SQL 로 이어 붙인다 —
 * 전체는 ix_preorder_payable(status, payable_from), 상품별은 ix_preorder_product_status(product_id, status, payable_from).
 */
@Component
public class PreorderBacklogQuery {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    PreorderBacklogQuery(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 상태별 수. 한 건도 없는 상태는 0 이다. productId 가 null 이면 전체를 센다. */
    public Map<PreorderStatus, Long> countByStatus(UUID productId) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        Map<PreorderStatus, Long> counts = new EnumMap<>(PreorderStatus.class);
        for (PreorderStatus status : PreorderStatus.values()) {
            counts.put(status, 0L);
        }
        jdbcTemplate.query("SELECT p.status, COUNT(*) FROM preorders p WHERE 1 = 1" + product(productId, params)
                + " GROUP BY p.status", params, rs -> {
            counts.put(PreorderStatus.valueOf(rs.getString(1)), rs.getLong(2));
        });
        return counts;
    }

    /** 결제를 기다리는(REGISTERED) 예약 중 결제 가능 시작이 그 시각 이전인 수. */
    public long countRegisteredPayableUntil(Instant payableUntil, UUID productId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("status", PreorderStatus.REGISTERED.name())
                .addValue("payableUntil", utc(payableUntil));
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM preorders p"
                + " WHERE p.status = :status AND p.payable_from <= :payableUntil" + product(productId, params),
                params, Long.class);
        return count == null ? 0 : count;
    }

    /** 그 상태 예약 중 가장 오래된 접수 시각. */
    public Optional<Instant> findOldestCreatedAt(PreorderStatus status, UUID productId) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("status", status.name());
        LocalDateTime oldest = jdbcTemplate.queryForObject("SELECT MIN(p.created_at) FROM preorders p"
                + " WHERE p.status = :status" + product(productId, params), params, LocalDateTime.class);
        return Optional.ofNullable(oldest).map(at -> at.toInstant(ZoneOffset.UTC));
    }

    private String product(UUID productId, MapSqlParameterSource params) {
        if (productId == null) {
            return "";
        }
        params.addValue("productId", UuidBinary.toBytes(productId));
        return " AND p.product_id = :productId";
    }

    /** DB 는 UTC 벽시계 시각을 담는다. Timestamp 로 주고받으면 JVM 시간대만큼 어긋난다. */
    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
