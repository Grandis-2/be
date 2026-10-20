package com.grandis.nova.batch.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.grandis.nova.batch.support.BatchIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class RunLifecycleTest extends BatchIntegrationTest {

    @Autowired
    ReconciliationService service;

    @Test
    void 관리자_요청을_가져가_실행하고_요청자를_그대로_둔다() {
        insertRun("admin", "REQUESTED");

        service.runRequested();

        assertThat(runs()).singleElement().satisfies(run -> assertThat(run)
                .containsEntry("status", "COMPLETED").containsEntry("requested_by", "admin"));
    }

    @Test
    void 요청이_없으면_아무것도_하지_않는다() {
        service.runRequested();

        assertThat(runs()).isEmpty();
    }

    @Test
    void 대기_중인_요청이_있으면_정기_실행은_행을_남기지_않고_건너뛴다() {
        insertRun("admin", "REQUESTED");

        service.runScheduled();

        assertThat(runs()).singleElement().satisfies(run -> assertThat(run).containsEntry("status", "REQUESTED"));
    }

    @Test
    void 오픈한_지_1시간이_안_된_회차가_있으면_정기_실행을_건너뛴다() {
        data.campaign("폰", ago(Duration.ofMinutes(10)), later(Duration.ofDays(7)));

        service.runScheduled();

        assertThat(runs()).isEmpty();
    }

    @Test
    void 오픈한_지_1시간이_지났으면_접수_기간이어도_정기_실행을_돈다() {
        data.campaign("폰", ago(Duration.ofHours(2)), later(Duration.ofDays(7)));

        service.runScheduled();

        assertThat(runs()).singleElement().satisfies(run -> assertThat(run).containsEntry("status", "COMPLETED"));
    }

    @Test
    void 회차가_마감되고_여유_시간이_지나면_한_번만_돈다() {
        data.campaign("폰", ago(Duration.ofDays(3)), ago(Duration.ofMinutes(50)));

        service.runAfterCampaignClose();
        service.runAfterCampaignClose();

        assertThat(runs()).singleElement().satisfies(run -> assertThat(run)
                .containsEntry("status", "COMPLETED").containsEntry("requested_by", null));
    }

    @Test
    void 마감_뒤_여유_시간이_안_지났으면_돌지_않는다() {
        data.campaign("폰", ago(Duration.ofDays(3)), ago(Duration.ofMinutes(30)));

        service.runAfterCampaignClose();

        assertThat(runs()).isEmpty();
    }

    @Test
    void batch_가_다시_뜨면_남은_실행_중을_실패로_끝내고_끝난_실행은_그대로_둔다() {
        jdbc.sql("""
                        INSERT INTO reconciliation_runs (id, status, started_at, cutoff_at, finished_at, held_count,
                                                         pending_sync_count, canceling_count, dead_letter_count,
                                                         created_at, updated_at)
                        VALUES (UUID_TO_BIN(UUID()), 'COMPLETED', NOW(6) - INTERVAL 1 DAY, NOW(6) - INTERVAL 1 DAY,
                                NOW(6) - INTERVAL 1 DAY, 0, 0, 0, 0, NOW(6) - INTERVAL 1 DAY, NOW(6) - INTERVAL 1 DAY)
                        """).update();
        jdbc.sql("""
                        INSERT INTO reconciliation_runs (id, status, started_at, cutoff_at, created_at, updated_at)
                        VALUES (UUID_TO_BIN(UUID()), 'RUNNING', NOW(6), NOW(6) - INTERVAL 45 MINUTE, NOW(6), NOW(6))
                        """).update();

        int cleaned = service.cleanupAfterRestart();

        assertThat(cleaned).isEqualTo(1);
        assertThat(runs()).extracting(run -> run.get("status")).containsExactly("COMPLETED", "FAILED");
        assertThat(runs().get(1)).containsEntry("error_message", "batch 재시작으로 중단");
        assertThat(runs().get(1).get("finished_at")).isNotNull();
        assertThat(service.cleanupAfterRestart()).isZero();
    }

    @Test
    void 끝나지_않고_남은_실행_중은_요청을_확인할_때_실패로_끝내_다음_실행을_막지_않는다() {
        // 프로세스는 살아 있는데 기록이 실패해 실행 중으로 남은 행 — 기동 정리가 돌지 않는다
        insertRunning("2 HOUR");

        service.runRequested();
        service.runScheduled();

        // 제한 시간 30분(기본값) + 여유 5분
        assertThat(runs()).extracting(run -> run.get("status")).containsExactly("FAILED", "COMPLETED");
        assertThat(runs().getFirst()).containsEntry("error_message", "끝나지 않은 실행을 정리(시작 뒤 35분 넘음)");
        assertThat(runs().getFirst().get("finished_at")).isNotNull();
    }

    @Test
    void 제한_시간_안에_있는_실행_중은_정리하지_않는다() {
        // 겹쳐 뜬 다른 batch 가 돌리는 중일 수 있다
        insertRunning("30 MINUTE");

        service.runRequested();

        assertThat(runs()).singleElement().satisfies(run -> assertThat(run).containsEntry("status", "RUNNING"));
    }

    @Test
    void batch_가_다시_뜨면_가져가지_못한_요청도_실패로_끝내_다음_실행을_막지_않는다() {
        insertRun("admin", "REQUESTED");

        int cleaned = service.cleanupAfterRestart();
        service.runScheduled();

        assertThat(cleaned).isEqualTo(1);
        // 요청 행은 DB 시계, 정기 실행 행은 앱 시계로 만들어 둘의 순서는 보지 않는다
        assertThat(runs()).extracting(run -> run.get("status")).containsExactlyInAnyOrder("FAILED", "COMPLETED");
        assertThat(runs()).filteredOn(run -> "FAILED".equals(run.get("status"))).singleElement()
                .satisfies(run -> assertThat(run)
                        .containsEntry("requested_by", "admin")
                        .containsEntry("error_message", "batch 재시작으로 중단"));
    }

    private void insertRun(String requestedBy, String status) {
        jdbc.sql("""
                        INSERT INTO reconciliation_runs (id, requested_by, status, created_at, updated_at)
                        VALUES (UUID_TO_BIN(UUID()), :by, :status, NOW(6), NOW(6))
                        """)
                .param("by", requestedBy).param("status", status).update();
    }

    /** 이만큼 전에 시작한 실행 중 행. interval 은 MySQL INTERVAL 식(예: "2 HOUR") */
    private void insertRunning(String interval) {
        jdbc.sql("""
                        INSERT INTO reconciliation_runs (id, status, started_at, cutoff_at, created_at, updated_at)
                        VALUES (UUID_TO_BIN(UUID()), 'RUNNING', NOW(6) - INTERVAL %1$s,
                                NOW(6) - INTERVAL %1$s - INTERVAL 45 MINUTE, NOW(6) - INTERVAL %1$s, NOW(6) - INTERVAL %1$s)
                        """.formatted(interval)).update();
    }

    private List<Map<String, Object>> runs() {
        return jdbc.sql("SELECT status, requested_by, error_message, finished_at FROM reconciliation_runs ORDER BY created_at")
                .query().listOfRows();
    }

    private static Instant ago(Duration duration) {
        return Instant.now().minus(duration);
    }

    private static Instant later(Duration duration) {
        return Instant.now().plus(duration);
    }
}
