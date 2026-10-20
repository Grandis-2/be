package com.grandis.nova.order.stock.persistence.adapter;

import com.grandis.nova.common.UuidBinary;
import com.grandis.nova.order.stock.domain.model.StockAdjustment;
import com.grandis.nova.order.stock.domain.model.StockCorrection;
import com.grandis.nova.order.stock.domain.repository.StockAdjustments;
import com.grandis.nova.order.stock.persistence.entity.StockAdjustmentJpaEntity;
import com.grandis.nova.order.stock.persistence.repository.StockAdjustmentJpaRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** 보정 기록. 넣기는 JPA(id 를 UUID v7 로 채운다), 읽기는 SQL 이다. 시각은 UTC 로 저장돼 있다(hibernate.jdbc.time_zone). */
@Repository
class JpaStockAdjustments implements StockAdjustments {

    private final StockAdjustmentJpaRepository adjustments;
    private final NamedParameterJdbcTemplate jdbc;

    JpaStockAdjustments(StockAdjustmentJpaRepository adjustments, NamedParameterJdbcTemplate jdbc) {
        this.adjustments = adjustments;
        this.jdbc = jdbc;
    }

    @Override
    public void record(StockAdjustment adjustment) {
        StockCorrection c = adjustment.correction();
        adjustments.saveAndFlush(new StockAdjustmentJpaEntity(c.optionId(), c.beforeReserved(), c.beforeSold(), c.afterReserved(),
                c.afterSold(), c.total(), adjustment.reason(), adjustment.requestedBy(), adjustment.createdAt()));
    }

    @Override
    public Map<UUID, Instant> lastCorrectedSince(Collection<UUID> optionIds, Instant since) {
        Map<UUID, Instant> corrected = new HashMap<>();
        if (optionIds.isEmpty()) {
            return corrected;
        }
        jdbc.query("""
                        SELECT option_id, MAX(created_at) AS corrected_at
                          FROM stock_adjustments
                         WHERE option_id IN (:ids) AND created_at > :since
                         GROUP BY option_id
                        """,
                new MapSqlParameterSource("ids", optionIds.stream().map(UuidBinary::toBytes).toList())
                        .addValue("since", LocalDateTime.ofInstant(since, ZoneOffset.UTC)),
                rs -> {
                    corrected.put(UuidBinary.fromBytes(rs.getBytes("option_id")),
                            rs.getObject("corrected_at", LocalDateTime.class).toInstant(ZoneOffset.UTC));
                });
        return corrected;
    }
}
