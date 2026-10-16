package com.grandis.nova.order.stock.reconcile;

import com.grandis.nova.order.stock.domain.model.StockBalance;
import com.grandis.nova.order.stock.domain.model.StockReconcileRun;
import com.grandis.nova.order.stock.domain.repository.ReconcileLock;
import com.grandis.nova.order.stock.domain.repository.StockExpectations;
import com.grandis.nova.order.stock.domain.repository.StockReconcileRuns;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * 재고 장부 대조 — 모든 옵션의 확보 · 판매를 주문 기준 수량과 맞대 보고({@link StockExpectations#findDrifted}), 어긋난 옵션마다
 * "재고 장부 대조 어긋남" ERROR 를 남기고 결과를 기록한다(stock_reconcile_runs). 고치지 않는다 — 관리자가 결과 목록을 보고 옵션마다 보정한다.
 *
 * - 하루 한 번 새벽 4시(KST), 실패하면 5시에 한 번 더(nova.stock.reconcile.cron — "-" 면 끈다). 표 전체를 읽는 문장이라 사전예약 피크를 피한다.
 *   경보는 마지막 성공 뒤 26시간이라, 4시 한 번의 일시 실패(배포 재시작 · DB 순단)는 5시가 메우고 이틀째 두 번 다 실패해야 울린다
 *   (Prometheus 권고 — 한 번의 실패를 견딜 수 없으면 더 자주 돌린다).
 * - 인스턴스가 여럿이면 DB 이름 잠금({@link ReconcileLock})을 잡은 하나만 돈다 — 기다리지 않고, 못 잡으면 건너뛴다. 잠금은 동시 실행만 막으므로,
 *   잠근 뒤 {@link #SCHEDULED_GAP} 안에 끝난 대조가 있으면 주기 실행은 건너뛴다 — 인스턴스마다 발화 시각이 조금씩 달라 연달아 도는 것과,
 *   4시가 성공한 날의 5시 회차를 함께 막는다. 실패는 기록하지 않으므로 4시가 실패한 날의 5시 회차는 돈다.
 *   잠금은 트랜잭션 바깥에서 잡고 커밋이 끝난 뒤 놓는다 — 놓은 뒤 잡은 인스턴스는 간격 판정에서 앞 기록을 반드시 본다(Rails 마이그레이션이
 *   잠근 뒤 이력을 다시 읽는 것과 같다). 읽기는 SQL 한 문장이라 잠금 없이도 그 시점에서 정확하다.
 * - ERROR 는 기록에 싣는 옵션(앞 {@link StockReconcileRun#DETAIL_LIMIT}개)만 옵션마다 남기고 나머지는 수만 한 줄로 — 처음 켤 때 과거 데이터로
 *   어긋남이 많아도 로그가 넘치지 않게.
 * - 실패(문장 시간 상한 · DB 오류)는 기록하지 않는다 — 마지막 성공 시각 지표(StockGauges)가 멈춰 경보로 드러난다.
 */
@Component
public class StockReconciler {

    private static final Logger log = LoggerFactory.getLogger(StockReconciler.class);

    /** 주기 실행은 이 안에 끝난 대조가 있으면 건너뛴다. 4시 → 5시 재시도 간격(1시간)보다 넉넉히 길고, 하루 주기보다 충분히 짧게. */
    static final Duration SCHEDULED_GAP = Duration.ofHours(2);

    private final StockExpectations expectations;
    private final StockReconcileRuns runs;
    private final ReconcileLock lock;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public StockReconciler(StockExpectations expectations, StockReconcileRuns runs, ReconcileLock lock,
                           PlatformTransactionManager transactionManager, Clock clock) {
        this.expectations = expectations;
        this.runs = runs;
        this.lock = lock;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @Scheduled(cron = "${nova.stock.reconcile.cron:0 0 4,5 * * *}", zone = "Asia/Seoul")
    void scheduled() {
        try {
            run(SCHEDULED_GAP);
        } catch (RuntimeException e) {
            log.error("재고 장부 대조 실패 — 다음 회차에 다시", e);
        }
    }

    /** 지금 바로 대조한다(최근 대조가 있어도). @return 끝난 대조. 다른 인스턴스가 돌고 있어 건너뛰었으면 비어 있다 */
    public Optional<StockReconcileRun> reconcile() {
        return run(Duration.ZERO);
    }

    /** @param gap 이 안에 끝난 대조가 있으면 건너뛴다. ZERO 면 보지 않는다 */
    Optional<StockReconcileRun> run(Duration gap) {
        Optional<Optional<StockReconcileRun>> locked = lock.runExclusively(() -> transaction.execute(status -> reconcileLocked(gap)));
        if (locked.isEmpty()) {
            log.info("재고 장부 대조 건너뜀 — 다른 인스턴스가 도는 중");
            return Optional.empty();
        }
        return locked.get();
    }

    private Optional<StockReconcileRun> reconcileLocked(Duration gap) {
        Instant startedAt = clock.instant();
        if (!gap.isZero() && runs.findLatestSummary().filter(last -> last.finishedAt().isAfter(startedAt.minus(gap))).isPresent()) {
            log.info("재고 장부 대조 건너뜀 — {} 안에 끝난 대조가 있다", gap);
            return Optional.empty();
        }
        StockExpectations.Drifts drifts = expectations.findDrifted(StockReconcileRun.DETAIL_LIMIT);
        StockReconcileRun run = StockReconcileRun.of(startedAt, clock.instant(), drifts.total(), drifts.first());
        report(run);
        runs.save(run);
        log.info("재고 장부 대조 끝 — 어긋난 옵션 {}개, {}ms", run.driftOptions(), run.finishedAt().toEpochMilli() - startedAt.toEpochMilli());
        return Optional.of(run);
    }

    private static void report(StockReconcileRun run) {
        for (StockBalance drift : run.drifts()) {
            log.error("재고 장부 대조 어긋남 optionId={} productId={} registered={} expectedReserved={} actualReserved={} "
                            + "expectedSold={} actualSold={}", drift.optionId(), drift.productId(), drift.registered(),
                    drift.expectedReserved(), drift.actualReserved(), drift.expectedSold(), drift.actualSold());
        }
        if (run.truncated()) {
            log.error("재고 장부 대조 어긋남 — 옵션마다 남긴 {}개 밖에 {}개가 더 있다, 관리자 대조 결과 · DB 로 확인", run.drifts().size(),
                    run.driftOptions() - run.drifts().size());
        }
    }
}
