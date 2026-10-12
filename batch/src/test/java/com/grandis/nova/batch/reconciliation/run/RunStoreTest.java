package com.grandis.nova.batch.reconciliation.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.grandis.nova.batch.reconciliation.run.RunStore.RunNotRunning;
import com.grandis.nova.batch.support.BatchIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 실행 행의 상태 전이 — 두 인스턴스가 겹쳐 떠도 같은 실행을 두 번 시작 · 완료하지 않는다 */
class RunStoreTest extends BatchIntegrationTest {

    private static final Duration MARGIN = Duration.ofMinutes(45);

    @Autowired
    RunStore store;

    @Test
    void 두_스레드가_동시에_정기_실행을_넣어도_실행은_하나뿐이다() throws Exception {
        List<Optional<ReconciliationRun>> started = together(() -> store.startScheduled(Instant.now(), MARGIN));

        assertThat(started).filteredOn(Optional::isPresent).hasSize(1);
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void 같은_요청을_두_스레드가_동시에_가져가도_하나만_가져간다() throws Exception {
        jdbc.sql("""
                        INSERT INTO reconciliation_runs (id, requested_by, status, created_at, updated_at)
                        VALUES (UUID_TO_BIN(UUID()), 'admin', 'REQUESTED', NOW(6), NOW(6))
                        """).update();

        List<Optional<ReconciliationRun>> claimed = together(() -> store.claimRequested(Instant.now(), MARGIN));

        assertThat(claimed).filteredOn(Optional::isPresent).singleElement()
                .satisfies(run -> assertThat(run.orElseThrow().getStatus()).isEqualTo(RunStatus.RUNNING));
        assertThat(jdbc.sql("SELECT status FROM reconciliation_runs").query(String.class).single()).isEqualTo("RUNNING");
    }

    @Test
    void 실행_중이_아닌_실행은_완료하지_않고_결과도_넣지_않는다() {
        UUID runId = store.startScheduled(Instant.now(), MARGIN).orElseThrow().getId();
        store.failUnfinished(Instant.now());

        assertThatThrownBy(() -> store.complete(runId, Instant.now(), RunCounts.ZERO,
                List.of(new NewFinding("TEST_CODE", "k-1", null, "{}"))))
                .isInstanceOf(RunNotRunning.class);
        assertThat(jdbc.sql("SELECT status FROM reconciliation_runs").query(String.class).single()).isEqualTo("FAILED");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM reconciliation_findings").query(Long.class).single()).isZero();
    }

    @Test
    void 이미_끝난_실행은_실패로_바꾸지_않는다() {
        UUID runId = store.startScheduled(Instant.now(), MARGIN).orElseThrow().getId();
        store.complete(runId, Instant.now(), RunCounts.ZERO, List.of());

        store.fail(runId, Instant.now(), "늦게 온 실패");

        assertThat(jdbc.sql("SELECT status FROM reconciliation_runs").query(String.class).single()).isEqualTo("COMPLETED");
    }

    /** 두 스레드가 같은 순간에 시작하도록 맞춰 부른다 */
    private static <T> List<T> together(Callable<T> call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return call.call();
                }));
            }
            ready.await();
            go.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private long count() {
        return jdbc.sql("SELECT COUNT(*) FROM reconciliation_runs").query(Long.class).single();
    }
}
