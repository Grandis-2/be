package com.grandis.nova.order.stock.reconcile;

import com.grandis.nova.order.stock.domain.model.StockReconcileRun;
import com.grandis.nova.order.stock.domain.repository.StockReconcileRuns;
import com.grandis.nova.order.support.MutableClock;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/** 대조 지표의 상태 — 아직 못 읽음(NaN, 기동 뒤 10분까지), 기록 없음(성공 시각 0 → 경보), 기록 있음 — 와 읽기 실패 뒤 값 유지. */
class StockGaugesTest {

    static final Instant FINISHED = Instant.parse("2026-10-15T19:00:30.250Z");

    final MeterRegistry registry = new SimpleMeterRegistry();
    final MutableClock clock = new MutableClock(Instant.parse("2026-10-16T01:00:00Z"));

    @Test
    @DisplayName("처음 읽기 전에는 둘 다 NaN — 기록이 있는지 모르는 채로 0 을 내 배포마다 경보가 울리지 않게")
    void unknownBeforeTheFirstRead() {
        new StockGauges(runs(() -> Optional.empty()), clock, registry);

        assertThat(lastSuccess()).isNaN();
        assertThat(driftOptions()).isNaN();
    }

    @Test
    @DisplayName("기동 뒤 10분이 지나도록 못 읽으면 성공 시각은 0 — 계속되는 읽기 실패가 NaN 에 숨어 경보가 영영 안 울리지 않게")
    void unreadableForTooLongCountsAsNoRun() {
        StockGauges gauges = new StockGauges(runs(() -> {
            throw new IllegalStateException("stock_reconcile_runs 권한 없음");
        }), clock, registry);
        gauges.refresh();
        clock.advance(StockGauges.UNKNOWN_LIMIT.minusSeconds(1));
        assertThat(lastSuccess()).as("한도 전").isNaN();

        clock.advance(Duration.ofSeconds(1));
        gauges.refresh();

        assertThat(lastSuccess()).isZero();
        assertThat(driftOptions()).isNaN();
    }

    @Test
    @DisplayName("기록이 없으면 성공 시각은 0(첫 대조까지 경보), 어긋난 수는 NaN(대조 안 했다)")
    void noRunYet() {
        new StockGauges(runs(() -> Optional.empty()), clock, registry).refresh();

        assertThat(lastSuccess()).isZero();
        assertThat(driftOptions()).isNaN();
    }

    @Test
    @DisplayName("기록이 있으면 마지막 성공의 Unix 시각(초)과 어긋난 옵션 수")
    void latestRun() {
        new StockGauges(runs(() -> Optional.of(new StockReconcileRun.Summary(FINISHED, 3))), clock, registry).refresh();

        assertThat(lastSuccess()).isEqualTo(FINISHED.toEpochMilli() / 1000.0);
        assertThat(driftOptions()).isEqualTo(3);
    }

    @Test
    @DisplayName("읽은 뒤 읽기가 실패하면 앞의 값을 둔다")
    void keepsTheLastValueWhenARefreshFails() {
        boolean[] failing = {false};
        StockGauges gauges = new StockGauges(runs(() -> {
            if (failing[0]) {
                throw new IllegalStateException("DB 끊김");
            }
            return Optional.of(new StockReconcileRun.Summary(FINISHED, 3));
        }), clock, registry);
        gauges.refresh();
        failing[0] = true;

        gauges.refresh();

        assertThat(lastSuccess()).isEqualTo(FINISHED.toEpochMilli() / 1000.0);
        assertThat(driftOptions()).isEqualTo(3);
    }

    private double lastSuccess() {
        return registry.get("order.stock.reconcile.last.success.timestamp").gauge().value();
    }

    private double driftOptions() {
        return registry.get("order.stock.drift.options").gauge().value();
    }

    private static StockReconcileRuns runs(Supplier<Optional<StockReconcileRun.Summary>> latest) {
        return new StockReconcileRuns() {
            @Override
            public void save(StockReconcileRun run) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<StockReconcileRun> findLatest() {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<StockReconcileRun.Summary> findLatestSummary() {
                return latest.get();
            }
        };
    }
}
