package com.grandis.nova.batch.reconciliation;

import com.grandis.nova.batch.reconciliation.check.ReconcileResult;
import com.grandis.nova.batch.reconciliation.check.Reconciler;
import com.grandis.nova.batch.reconciliation.config.ReconciliationProperties;
import com.grandis.nova.batch.reconciliation.mock.MockLedgerClient;
import com.grandis.nova.batch.reconciliation.mock.MockLedgerUnavailable;
import com.grandis.nova.batch.reconciliation.run.ReconciliationRun;
import com.grandis.nova.batch.reconciliation.run.RunStore;
import com.grandis.nova.batch.reconciliation.run.RunStore.RunNotRunning;
import com.grandis.nova.batch.reconciliation.snapshot.BeSnapshot;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 검사 실행 한 번을 처음부터 끝까지 돌린다.
 *
 * <ol>
 *   <li>실행 행을 실행 중으로 — 정기 실행은 넣고, 관리자 실행은 요청을 가져간다. 시작 시각 · 기준 시각(시작 − 여유)을 적고 커밋</li>
 *   <li>검사 트랜잭션(REPEATABLE READ · 읽기 전용, 한 스냅샷)에서 be 예약과 Mock 원장을 맞대고 be 내부 검사를 돈다</li>
 *   <li>찾은 불일치를 넣고 완료로 — 한 트랜잭션. 실패하면 판정 불가(FAILED)로 끝내고 결과는 남기지 않는다</li>
 * </ol>
 * 주기 작업은 스케줄러 스레드 하나에서 차례로 돌아 서로 겹치지 않는다. 대기 · 실행 중인 실행은 DB 가 하나로 막는다.
 */
@Service
public class ReconciliationService {

    static final String UNEXPECTED_FAILURE = "검사 중 오류";

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final RunStore runs;
    private final BeSnapshot be;
    private final MockLedgerClient mock;
    private final Reconciler reconciler;
    private final CampaignCalendar calendar;
    private final ReconciliationProperties properties;
    private final Clock clock;
    private final TransactionTemplate snapshot;

    ReconciliationService(RunStore runs, BeSnapshot be, MockLedgerClient mock, Reconciler reconciler,
                          CampaignCalendar calendar, ReconciliationProperties properties, Clock clock,
                          PlatformTransactionManager transactionManager) {
        this.runs = runs;
        this.be = be;
        this.mock = mock;
        this.reconciler = reconciler;
        this.calendar = calendar;
        this.properties = properties;
        this.clock = clock;
        this.snapshot = new TransactionTemplate(transactionManager);
        this.snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.snapshot.setReadOnly(true);
    }

    /** 관리자가 남긴 요청이 있으면 가져가 돌린다. 몰리는 구간 확인은 요청을 받는 preorder 가 한다(409) */
    public void runRequested() {
        runs.claimRequested(clock.instant(), properties.cutoffMargin()).ifPresent(this::execute);
    }

    /** 정기 실행. 회차 오픈 뒤 몰리는 구간이면 행을 남기지 않고 건너뛴다 */
    public void runScheduled() {
        Instant now = clock.instant();
        if (calendar.inIntakeRush(now, properties.intakeRushWindow())) {
            log.info("정기 실행 건너뜀 — 오픈한 지 {} 가 안 된 회차가 있다", properties.intakeRushWindow());
            return;
        }
        runs.startScheduled(now, properties.cutoffMargin()).ifPresent(this::execute);
    }

    /** 회차가 마감되고 여유 시간이 지났는데 그 뒤에 시작한 실행이 없으면 한 번 돌린다 */
    public void runAfterCampaignClose() {
        Instant now = clock.instant();
        calendar.latestCloseReadyAt(now, properties.cutoffMargin(), properties.campaignCloseLookback())
                .filter(readyAt -> !runs.startedSince(readyAt))
                .ifPresent(readyAt -> {
                    log.info("회차 마감 뒤 실행 — 마감 + {} = {}", properties.cutoffMargin(), readyAt);
                    runScheduled();
                });
    }

    /** batch 가 다시 떴을 때 남은 대기 · 실행 중을 실패로 끝낸다(10-12 결정) */
    public int cleanupAfterRestart() {
        int count = runs.failUnfinished(clock.instant());
        if (count > 0) {
            log.warn("batch 재시작으로 끝나지 않은 실행 {}건을 실패로 바꿨다", count);
        }
        return count;
    }

    void execute(ReconciliationRun run) {
        try {
            ReconcileResult result = snapshot.execute(status -> reconciler.reconcile(
                    be.preorders(run.getProductId(), properties.chunkSize()), mock.entries(),
                    run.getProductId(), run.getCutoffAt()));
            runs.complete(run.getId(), clock.instant(), result.counts(), result.findings());
            log.info("정합성 검사 완료 runId={} 불일치 {}건 {}", run.getId(), result.findings().size(), result.counts());
        } catch (MockLedgerUnavailable e) {
            log.warn("정합성 검사 판정 불가 runId={} — {}", run.getId(), e.getMessage());
            runs.fail(run.getId(), clock.instant(), e.getMessage());
        } catch (RunNotRunning e) {
            // 검사하는 동안 다른 곳(기동 정리 · 겹쳐 뜬 인스턴스)이 이 실행을 먼저 끝냈다. 결과는 넣지 않았다
            log.warn("정합성 검사 결과를 버림 — {}", e.getMessage());
        } catch (RuntimeException e) {
            log.error("정합성 검사 실패 runId={}", run.getId(), e);
            runs.fail(run.getId(), clock.instant(), UNEXPECTED_FAILURE + " — " + e.getClass().getSimpleName());
        }
    }
}
