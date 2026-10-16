package com.grandis.nova.batch.reconciliation;

import com.grandis.nova.batch.reconciliation.check.ReconcileResult;
import com.grandis.nova.batch.reconciliation.check.Reconciler;
import com.grandis.nova.batch.reconciliation.check.RunScope;
import com.grandis.nova.batch.reconciliation.check.TooManyFindings;
import com.grandis.nova.batch.reconciliation.config.MockLedgerProperties;
import com.grandis.nova.batch.reconciliation.config.ReconciliationProperties;
import com.grandis.nova.batch.reconciliation.mock.MockLedgerClient;
import com.grandis.nova.batch.reconciliation.mock.MockLedgerUnavailable;
import com.grandis.nova.batch.reconciliation.run.ReconciliationRun;
import com.grandis.nova.batch.reconciliation.run.RunStore;
import com.grandis.nova.batch.reconciliation.run.RunStore.RunNotRunning;
import com.grandis.nova.batch.reconciliation.snapshot.BeSnapshot;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 검사 실행 한 번을 처음부터 끝까지 돌린다.
 *
 * <ol>
 *   <li>실행 행을 실행 중으로 — 정기 실행은 넣고, 관리자 실행은 요청을 가져간다. 시작 시각 · 기준 시각(시작 − 판정 보류 여유)을 적고 커밋</li>
 *   <li>검사 트랜잭션(REPEATABLE READ · 읽기 전용, 한 스냅샷)에서 be 예약과 Mock 원장을 맞대고 be 내부 검사를 돈다.
 *       Mock 목록 조회(HTTP)를 이 트랜잭션 안에서 기다린다 — 일부러다. be 를 한 스냅샷으로, Mock 보다 먼저 읽어야 처리 중인 건이
 *       불일치로 잡히지 않는다. 잠금 없는 일관 읽기라 행을 잠그지 않고, 대신 연결 하나와 read view 를 잡으므로 목록 전체 기한
 *       (external.mock.max-duration)을 넘으면 판정 불가로 끊는다</li>
 *   <li>찾은 불일치를 넣고 완료로 — 한 트랜잭션. 실패하면 판정 불가(FAILED)로 끝내고 결과는 남기지 않는다</li>
 * </ol>
 * 주기 작업은 스케줄러 스레드 하나에서 차례로 돌아 서로 겹치지 않는다. 대기 · 실행 중인 실행은 DB 가 하나로 막는다.
 *
 * 실행 중으로 남은 행은 이후 실행을 모두 막으므로 끝나지 않게 두지 않는다. 검사 트랜잭션은 제한 시간(run-timeout)을 넘으면 진행 중인
 * DB 조회를 끊고 판정 불가로 끝낸다 — 조회가 걸려도 스레드가 풀린다. 그래도 남은 행(기록 실패 등)은 요청을 확인할 때 정리한다.
 * 그때는 이 batch 가 검사를 돌리고 있지 않으므로, 시작한 지 제한 시간 + 여유가 넘은 실행 중은 주인이 없다.
 */
@Service
public class ReconciliationService {

    static final String UNEXPECTED_FAILURE = "검사 중 오류";

    // 검사 트랜잭션이 제한 시간에 끊긴 뒤 결과 넣기 · 실패 기록까지 끝나는 여유. 겹쳐 뜬 인스턴스의 실행을 잘못 정리하지 않게 넉넉히 둔다
    private static final Duration STALE_MARGIN = Duration.ofMinutes(5);

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
                          CampaignCalendar calendar, ReconciliationProperties properties, MockLedgerProperties mockLedger,
                          Clock clock, PlatformTransactionManager transactionManager) {
        // 검사 트랜잭션에서 DB 조회 없이 도는 구간은 Mock 목록뿐이다. 그 기한이 제한 시간 안이어야 실행이 제한 시간 + 몇 초 안에 끝나고,
        // 시작한 지 제한 시간 + 여유가 넘은 실행 중을 주인 없는 행으로 볼 수 있다
        if (properties.runTimeout().compareTo(mockLedger.maxDuration()) < 0) {
            throw new IllegalArgumentException("nova.batch.reconciliation.run-timeout 은 external.mock.max-duration 이상이어야 한다");
        }
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
        // 남은 시간을 이 트랜잭션의 JDBC 조회마다 조회 제한 시간으로 건다. 넘긴 뒤의 조회는 바로 실패한다
        this.snapshot.setTimeout(Math.toIntExact(properties.runTimeout().toSeconds()));
    }

    /**
     * 끝나지 않고 남은 실행 중을 정리하고, 관리자가 남긴 요청이 있으면 가져가 돌린다. 몰리는 구간 확인은 요청을 받는 preorder 가 한다(409)
     */
    public void runRequested() {
        Instant now = clock.instant();
        Duration staleAfter = properties.runTimeout().plus(STALE_MARGIN);
        int stale = runs.failStale(now, staleAfter);
        if (stale > 0) {
            log.warn("끝나지 않은 실행 {}건을 실패로 바꿨다 — 시작한 지 {} 가 넘었다", stale, staleAfter);
        }
        runs.claimRequested(now, properties.holdMargin()).ifPresent(this::execute);
    }

    /** 정기 실행. 회차 오픈 뒤 몰리는 구간이면 행을 남기지 않고 건너뛴다 */
    public void runScheduled() {
        Instant now = clock.instant();
        if (calendar.inIntakeRush(now, properties.intakeRushWindow())) {
            log.info("정기 실행 건너뜀 — 오픈한 지 {} 가 안 된 회차가 있다", properties.intakeRushWindow());
            return;
        }
        runs.startScheduled(now, properties.holdMargin()).ifPresent(this::execute);
    }

    /** 회차가 마감되고 멈춤 기준만큼 지났는데 그 뒤에 시작한 실행이 없으면 한 번 돌린다 — 마감 직전 신청의 처리가 끝난 뒤에 본다 */
    public void runAfterCampaignClose() {
        Instant now = clock.instant();
        calendar.latestCloseReadyAt(now, properties.stuckAfter(), properties.campaignCloseLookback())
                .filter(readyAt -> !runs.startedSince(readyAt))
                .ifPresent(readyAt -> {
                    log.info("회차 마감 뒤 실행 — 마감 + {} = {}", properties.stuckAfter(), readyAt);
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
            Instant startedAt = run.getStartedAt();
            RunScope scope = new RunScope(run.getProductId(), run.getCutoffAt(), startedAt.minus(properties.stuckAfter()),
                    properties.maxFindings(), be::exists);
            ReconcileResult result = snapshot.execute(status -> reconciler.reconcile(
                    be.preorders(run.getProductId(), properties.chunkSize()), mock.entries(), scope));
            runs.complete(run.getId(), clock.instant(), result.counts(), result.findings());
            log.info("정합성 검사 완료 runId={} 불일치 {}건 {}", run.getId(), result.findings().size(), result.counts());
        } catch (MockLedgerUnavailable | TooManyFindings e) {
            log.warn("정합성 검사 판정 불가 runId={} — {}", run.getId(), e.getMessage());
            runs.fail(run.getId(), clock.instant(), e.getMessage());
        } catch (TransactionTimedOutException | QueryTimeoutException e) {
            log.warn("정합성 검사 판정 불가 runId={} — 검사 제한 시간을 넘음", run.getId(), e);
            runs.fail(run.getId(), clock.instant(), "검사 제한 시간(" + readable(properties.runTimeout()) + ")을 넘음");
        } catch (RunNotRunning e) {
            // 검사하는 동안 다른 곳(기동 정리 · 겹쳐 뜬 인스턴스)이 이 실행을 먼저 끝냈다. 결과는 넣지 않았다
            log.warn("정합성 검사 결과를 버림 — {}", e.getMessage());
        } catch (RuntimeException e) {
            log.error("정합성 검사 실패 runId={}", run.getId(), e);
            runs.fail(run.getId(), clock.instant(), UNEXPECTED_FAILURE + " — " + e.getClass().getSimpleName());
        }
    }

    private static String readable(Duration duration) {
        return duration.toSecondsPart() == 0 && duration.toMinutes() > 0
                ? duration.toMinutes() + "분"
                : duration.toSeconds() + "초";
    }
}
