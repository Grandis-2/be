package com.grandis.nova.order.stock.reconcile;

import com.grandis.nova.order.stock.domain.model.StockReconcileRun;
import com.grandis.nova.order.stock.domain.repository.StockReconcileRuns;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 재고 장부 대조의 지표. 대조는 인스턴스 하나만 돌지만 기록은 DB 에 있으므로, 모든 인스턴스가 주기마다 마지막 기록을 읽어 같은 값을 낸다.
 * - order.stock.reconcile.last.success.timestamp(초): 마지막으로 끝난 대조의 Unix 시각. 지난 시간은 경보 식이 계산한다
 *   (time() - x — Prometheus 권고 "Timestamps, not time since"). 갱신이 멈춰도 값이 오래돼 보일 뿐이라 경보 쪽으로 틀린다.
 *   기록이 없으면 0 이다(권고 "Avoid missing metrics") — 첫 대조가 끝날 때까지 경보가 울린다.
 * - order.stock.drift.options: 마지막 대조의 어긋난 옵션 수. 경보는 걸지 않는다(대시보드용) — 어긋남은 관리자 대조 결과 조회로 본다.
 *   기록이 없으면 NaN 이다 — 0 은 "대조했고 어긋남 없음"으로 읽힌다.
 * 기동하자마자 한 번 읽고 그 뒤 주기마다 읽는다. 아직 한 번도 못 읽었으면 둘 다 NaN 이다 — 기록이 있는지 모르는 채로 0 을 내면 배포마다
 * 경보가 울린다. 다만 기동 뒤 {@link #UNKNOWN_LIMIT} 이 지나도록 못 읽으면(표 권한 누락 같은 계속되는 실패) 성공 시각은 0 으로 낸다 — NaN 은
 * 경보 식이 거짓이 되어 영영 울리지 않는다. 읽은 뒤 실패하면 앞의 값을 그대로 둔다.
 */
@Component
class StockGauges {

    private static final Logger log = LoggerFactory.getLogger(StockGauges.class);

    /** 기동 뒤 이만큼 못 읽으면 성공 시각을 모름(NaN)이 아니라 0 으로 낸다. 갱신 주기(60초)의 여러 배라 잠깐의 DB 끊김은 넘긴다. */
    static final Duration UNKNOWN_LIMIT = Duration.ofMinutes(10);

    private final StockReconcileRuns runs;
    private final Clock clock;
    private final Instant unknownUntil;
    /** null = 아직 못 읽음, empty = 기록 없음 */
    private final AtomicReference<Optional<StockReconcileRun.Summary>> latest = new AtomicReference<>();

    StockGauges(StockReconcileRuns runs, Clock clock, MeterRegistry registry) {
        this.runs = runs;
        this.clock = clock;
        this.unknownUntil = clock.instant().plus(UNKNOWN_LIMIT);
        Gauge.builder("order.stock.reconcile.last.success.timestamp", this, StockGauges::lastSuccessEpochSeconds)
                .baseUnit("seconds").description("마지막으로 끝난 재고 장부 대조의 Unix 시각(기록이 없으면 0)").register(registry);
        Gauge.builder("order.stock.drift.options", this, StockGauges::driftOptions)
                .description("마지막 재고 장부 대조에서 어긋난 옵션 수").register(registry);
    }

    @Scheduled(fixedDelayString = "${nova.stock.gauge-refresh-interval:60s}")
    void refresh() {
        try {
            latest.set(runs.findLatestSummary());
        } catch (RuntimeException e) {
            log.warn("재고 장부 대조 지표 갱신 실패 — 앞의 값을 둔다", e);
        }
    }

    double lastSuccessEpochSeconds() {
        Optional<StockReconcileRun.Summary> summary = latest.get();
        if (summary == null) {
            return clock.instant().isBefore(unknownUntil) ? Double.NaN : 0.0;
        }
        return summary.map(s -> s.finishedAt().toEpochMilli() / 1000.0).orElse(0.0);
    }

    double driftOptions() {
        Optional<StockReconcileRun.Summary> summary = latest.get();
        return summary == null ? Double.NaN : summary.map(s -> (double) s.driftOptions()).orElse(Double.NaN);
    }
}
