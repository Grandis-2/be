package com.grandis.nova.batch.reconciliation.run;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 실행 행의 상태를 바꾼다. 바꿀 때마다 짧은 트랜잭션 하나로 커밋한다 — 검사(스냅샷 읽기)는 그 사이에 따로 돈다.
 * 대기 · 실행 중인 실행은 하나뿐이라(active_marker UNIQUE) 정기 실행을 넣다가 1062 가 나면 건너뛴다.
 */
@Component
public class RunStore {

    static final String RESTART_MESSAGE = "batch 재시작으로 중단";

    private static final Logger log = LoggerFactory.getLogger(RunStore.class);
    private static final List<RunStatus> ACTIVE = List.of(RunStatus.REQUESTED, RunStatus.RUNNING);

    private final RunRepository runs;
    private final FindingRepository findings;
    private final TransactionTemplate tx;

    RunStore(RunRepository runs, FindingRepository findings, PlatformTransactionManager transactionManager) {
        this.runs = runs;
        this.findings = findings;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** 정기 실행을 넣는다. 대기 · 실행 중인 실행이 있으면 넣지 않는다(행을 남기지 않고 건너뜀) */
    public Optional<ReconciliationRun> startScheduled(Instant now, Duration cutoffMargin) {
        try {
            return Optional.ofNullable(tx.execute(status -> runs.existsByStatusIn(ACTIVE)
                    ? null
                    : runs.saveAndFlush(ReconciliationRun.scheduled(now, now.minus(cutoffMargin)))));
        } catch (DataIntegrityViolationException e) {
            // 확인과 넣기 사이에 관리자 요청이 들어왔다
            log.info("정기 실행 건너뜀 — 대기 · 실행 중인 실행이 있다");
            return Optional.empty();
        }
    }

    /** 관리자가 남긴 요청을 가져가 실행 중으로 바꾼다 */
    public Optional<ReconciliationRun> claimRequested(Instant now, Duration cutoffMargin) {
        return Optional.ofNullable(tx.execute(status -> runs.findFirstByStatusOrderByCreatedAtAsc(RunStatus.REQUESTED)
                .map(run -> {
                    run.start(now, now.minus(cutoffMargin));
                    return runs.saveAndFlush(run);
                })
                .orElse(null)));
    }

    /** 이 시각 이후에 시작한 실행이 있는가 — 회차 마감 뒤 실행을 한 번만 돌리는 데 쓴다 */
    public boolean startedSince(Instant at) {
        return Boolean.TRUE.equals(tx.execute(status -> runs.existsByStartedAtGreaterThanEqual(at)));
    }

    /** batch 가 다시 떴을 때 남은 대기 · 실행 중을 실패로 끝낸다. 몇 건을 정리했는지 돌려준다 */
    public int failUnfinished(Instant now) {
        Integer count = tx.execute(status -> runs.failUnfinished(now, RESTART_MESSAGE));
        return count == null ? 0 : count;
    }

    public void fail(UUID runId, Instant now, String message) {
        tx.executeWithoutResult(status -> {
            ReconciliationRun run = runs.findById(runId).orElseThrow();
            run.fail(now, message);
        });
    }

    /**
     * 찾은 불일치를 넣고 실행을 완료로 바꾼다. 둘이 한 트랜잭션이다 — 완료로 보이는 실행에는 결과가 다 들어 있다.
     * 처음 발견 시각은 범위가 같은 앞 완료 실행에 같은 (코드, 대상)이 있으면 그 값을, 없으면 이 실행의 시작 시각이다.
     */
    public void complete(UUID runId, Instant now, RunCounts counts, List<NewFinding> found) {
        tx.executeWithoutResult(status -> {
            ReconciliationRun run = runs.findById(runId).orElseThrow();
            Map<String, Instant> earlier = firstDetectedInPrevious(run);
            findings.saveAll(found.stream()
                    .map(f -> new ReconciliationFinding(runId, f.checkCode(), f.targetKey(), f.beSnapshot(),
                            f.mockSnapshot(), earlier.getOrDefault(key(f.checkCode(), f.targetKey()), run.getStartedAt())))
                    .toList());
            run.complete(now, counts);
        });
    }

    private Map<String, Instant> firstDetectedInPrevious(ReconciliationRun run) {
        Map<String, Instant> earlier = new HashMap<>();
        runs.findPreviousCompleted(run.getCreatedAt(), run.getProductId(), Limit.of(1)).stream().findFirst()
                .ifPresent(previous -> findings.findAllByRunId(previous.getId()).forEach(f ->
                        earlier.put(key(f.getCheckCode(), f.getTargetKey()), f.getFirstDetectedAt())));
        return earlier;
    }

    private static String key(String checkCode, String targetKey) {
        return checkCode + '\u0000' + targetKey;
    }
}
