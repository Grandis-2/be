package com.grandis.nova.preorder.syncjob.domain;

import com.grandis.nova.common.UuidBinary;
import com.grandis.nova.preorder.syncjob.SyncJobStatus;
import com.grandis.nova.preorder.syncjob.SyncJobType;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 끝나지 않은 작업 수. 성공 · 무효화한 작업은 예약 수만큼 쌓여 세지 않는다 — 읽는 행이 밀린 양에 비례한다
 * (ix_sync_job_lease 의 status 범위). 상품 조건은 예약 표를 읽기 전용으로 조인한다.
 */
@Component
public class UnfinishedJobCountQuery {

    /** 아직 결과가 나지 않았거나 포기해 사람이 봐야 하는 작업. */
    public static final List<SyncJobStatus> UNFINISHED = List.of(SyncJobStatus.PENDING, SyncJobStatus.PROCESSING,
            SyncJobStatus.RETRY_SCHEDULED, SyncJobStatus.DEAD_LETTER);

    private final NamedParameterJdbcTemplate jdbcTemplate;

    UnfinishedJobCountQuery(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 종류 → 상태 → 수. 한 건도 없는 칸은 0 이다. productId 가 null 이면 전체를 센다. */
    public Map<SyncJobType, Map<SyncJobStatus, Long>> count(UUID productId) {
        Map<SyncJobType, Map<SyncJobStatus, Long>> counts = new EnumMap<>(SyncJobType.class);
        for (SyncJobType jobType : SyncJobType.values()) {
            Map<SyncJobStatus, Long> byStatus = new EnumMap<>(SyncJobStatus.class);
            UNFINISHED.forEach(status -> byStatus.put(status, 0L));
            counts.put(jobType, byStatus);
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("statuses", UNFINISHED.stream().map(Enum::name).toList());
        String join = "";
        if (productId != null) {
            join = " JOIN preorders p ON p.id = j.preorder_id AND p.product_id = :productId";
            params.addValue("productId", UuidBinary.toBytes(productId));
        }
        jdbcTemplate.query("SELECT j.job_type, j.status, COUNT(*) FROM preorder_sync_jobs j" + join
                + " WHERE j.status IN (:statuses) GROUP BY j.job_type, j.status", params, rs -> {
            counts.get(SyncJobType.valueOf(rs.getString(1))).put(SyncJobStatus.valueOf(rs.getString(2)), rs.getLong(3));
        });
        return counts;
    }

    /**
     * 포기한(DEAD_LETTER) 작업 수를 종류 · 상품별로. 0 인 칸은 없다. 상태 범위로 그 작업만 읽고 예약 표는 상품을 알려고 조인한다
     * — 읽는 행이 포기한 작업 수에 비례한다.
     */
    public Map<SyncJobType, Map<UUID, Long>> countDeadLetterByProduct() {
        Map<SyncJobType, Map<UUID, Long>> counts = new EnumMap<>(SyncJobType.class);
        for (SyncJobType jobType : SyncJobType.values()) {
            counts.put(jobType, new HashMap<>());
        }
        jdbcTemplate.query("SELECT j.job_type, p.product_id, COUNT(*) FROM preorder_sync_jobs j"
                        + " JOIN preorders p ON p.id = j.preorder_id WHERE j.status = :status GROUP BY j.job_type, p.product_id",
                new MapSqlParameterSource("status", SyncJobStatus.DEAD_LETTER.name()), rs -> {
                    counts.get(SyncJobType.valueOf(rs.getString(1))).put(UuidBinary.fromBytes(rs.getBytes(2)), rs.getLong(3));
                });
        return counts;
    }
}
