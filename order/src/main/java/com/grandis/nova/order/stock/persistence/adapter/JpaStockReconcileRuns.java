package com.grandis.nova.order.stock.persistence.adapter;

import com.grandis.nova.order.stock.domain.model.StockBalance;
import com.grandis.nova.order.stock.domain.model.StockReconcileRun;
import com.grandis.nova.order.stock.domain.repository.StockReconcileRuns;
import com.grandis.nova.order.stock.persistence.entity.StockReconcileRunJpaEntity;
import com.grandis.nova.order.stock.persistence.repository.StockReconcileRunJpaRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 대조 기록. 넣기는 JPA(id 를 UUID v7 로 채운다), 읽기는 SQL 이다 — 최근 것은 끝난 시각으로 고른다(id 의 같은 밀리초 안 순서는 보장되지 않는다).
 * 옵션 목록(detail)은 {@link Detail} 모양의 JSON 이다.
 */
@Repository
class JpaStockReconcileRuns implements StockReconcileRuns {

    private final StockReconcileRunJpaRepository runs;
    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    JpaStockReconcileRuns(StockReconcileRunJpaRepository runs, JdbcTemplate jdbc, JsonMapper json) {
        this.runs = runs;
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void save(StockReconcileRun run) {
        Detail detail = new Detail(run.drifts().stream().map(Option::from).toList(), run.truncated());
        runs.saveAndFlush(new StockReconcileRunJpaEntity(run.startedAt(), run.finishedAt(), run.driftOptions(), json.writeValueAsString(detail)));
    }

    @Override
    public Optional<StockReconcileRun> findLatest() {
        return jdbc.query("SELECT started_at, finished_at, drift_options, detail FROM stock_reconcile_runs ORDER BY finished_at DESC LIMIT 1",
                (rs, rowNum) -> {
                    Detail detail = json.readValue(rs.getString("detail"), Detail.class);
                    return new StockReconcileRun(instant(rs, "started_at"), instant(rs, "finished_at"), rs.getInt("drift_options"),
                            detail.options().stream().map(Option::toBalance).toList(), detail.truncated());
                }).stream().findFirst();
    }

    @Override
    public Optional<StockReconcileRun.Summary> findLatestSummary() {
        return jdbc.query("SELECT finished_at, drift_options FROM stock_reconcile_runs ORDER BY finished_at DESC LIMIT 1",
                (rs, rowNum) -> new StockReconcileRun.Summary(instant(rs, "finished_at"), rs.getInt("drift_options"))).stream().findFirst();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, LocalDateTime.class).toInstant(ZoneOffset.UTC);
    }

    record Detail(List<Option> options, boolean truncated) {
    }

    record Option(UUID optionId, UUID productId, boolean registered, int total, long expectedReserved, int actualReserved,
                  long expectedSold, int actualSold) {

        static Option from(StockBalance b) {
            return new Option(b.optionId(), b.productId(), b.registered(), b.total(), b.expectedReserved(), b.actualReserved(),
                    b.expectedSold(), b.actualSold());
        }

        StockBalance toBalance() {
            return new StockBalance(optionId, productId, registered, total, actualReserved, actualSold, expectedReserved, expectedSold);
        }
    }
}
