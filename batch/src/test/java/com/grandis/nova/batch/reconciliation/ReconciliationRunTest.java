package com.grandis.nova.batch.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.grandis.nova.batch.support.BatchIntegrationTest;
import com.grandis.nova.batch.support.RecordingCheck;
import com.grandis.nova.batch.support.TestData;
import com.grandis.nova.batch.support.TestData.BePreorderRow;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

@Import(ReconciliationRunTest.Checks.class)
@TestPropertySource(properties = "nova.batch.reconciliation.max-findings=2")
class ReconciliationRunTest extends BatchIntegrationTest {

    private static final Instant TWO_HOURS_AGO = Instant.now().minus(Duration.ofHours(2));

    @TestConfiguration(proxyBeanMethods = false)
    static class Checks {

        @Bean
        RecordingCheck recordingCheck() {
            return new RecordingCheck();
        }
    }

    @Autowired
    ReconciliationService service;

    @Autowired
    RecordingCheck check;

    @BeforeEach
    void resetCheck() {
        check.reset();
    }

    @Test
    void 정기_실행은_be_예약과_Mock_원장을_맞대고_건수와_함께_완료로_남긴다() {
        data.preorder("폰", "t-1", "PENDING_SYNC", "PENDING", TWO_HOURS_AGO);
        data.preorder("폰", "t-2", "PENDING_SYNC", "DEAD_LETTER", TWO_HOURS_AGO);
        data.preorder("폰", "t-3", "CANCELING", "SUCCEEDED", TWO_HOURS_AGO);
        BePreorderRow reserved = data.preorder("폰", "t-4", "RESERVED", "SUCCEEDED", TWO_HOURS_AGO);
        MOCK.active("t-4", "R-TEST", reserved.customerId().toString(), reserved.productId().toString(),
                TestData.sku("폰"), TWO_HOURS_AGO);
        MOCK.active("t-5", "R-ONLY", UUID.randomUUID().toString(), reserved.productId().toString(), "SKU", TWO_HOURS_AGO);

        service.runScheduled();

        Map<String, Object> run = onlyRun();
        assertThat(run).containsEntry("status", "COMPLETED")
                .containsEntry("requested_by", null)
                .containsEntry("product_id", null)
                .containsEntry("held_count", 0)
                .containsEntry("pending_sync_count", 1)
                .containsEntry("dead_letter_count", 1)
                .containsEntry("canceling_count", 1)
                .containsEntry("cutoff_minutes", 45L);
        assertThat(run.get("finished_at")).isNotNull();
        // 페이지 2건씩 세 번 받는 동안 키 순서대로 한 번씩 — be 에만 · 양쪽 · Mock 에만 모두
        assertThat(check.keys()).containsExactly("t-1", "t-2", "t-3", "t-4", "t-5");
        assertThat(count("reconciliation_findings")).isZero();
    }

    @Test
    void 기준_시각_뒤에_바뀐_예약은_판정하지_않고_판정_보류로_센다() {
        data.preorder("폰", "t-1", "REGISTERED", "SUCCEEDED", Instant.now());
        data.preorder("폰", "t-2", "REGISTERED", "SUCCEEDED", TWO_HOURS_AGO);

        service.runScheduled();

        assertThat(onlyRun()).containsEntry("status", "COMPLETED").containsEntry("held_count", 1);
        assertThat(check.keys()).containsExactly("t-2");
    }

    @Test
    void Mock_목록을_끝까지_받지_못하면_판정_불가로_끝내고_결과를_남기지_않는다() {
        for (String key : List.of("m-1", "m-2", "m-3")) {
            MOCK.active(key, "R-" + key, UUID.randomUUID().toString(), UUID.randomUUID().toString(), "SKU", TWO_HOURS_AGO);
        }
        check.flag("m-1");
        MOCK.failAtPage(2, 503);

        service.runScheduled();

        assertThat(onlyRun()).containsEntry("status", "FAILED")
                .containsEntry("error_message", "Mock 목록 조회 실패(2페이지, HTTP 503)")
                .containsEntry("held_count", null);
        assertThat(count("reconciliation_findings")).isZero();
    }

    @Test
    void 찾은_불일치는_처음_발견_시각을_범위가_같은_앞_실행에서_이어받는다() {
        MOCK.active("g-1", "R-GHOST", UUID.randomUUID().toString(), UUID.randomUUID().toString(), "SKU", TWO_HOURS_AGO);
        check.flag("g-1");

        service.runScheduled();
        service.runScheduled();

        List<Map<String, Object>> findings = jdbc.sql("""
                        SELECT f.check_code, f.target_key, f.be_snapshot, f.mock_snapshot, f.action_status,
                               f.first_detected_at, r.started_at
                          FROM reconciliation_findings f JOIN reconciliation_runs r ON r.id = f.run_id
                         ORDER BY r.created_at
                        """)
                .query().listOfRows();
        assertThat(findings).hasSize(2);
        LocalDateTime firstRunStarted = (LocalDateTime) findings.get(0).get("started_at");
        assertThat(findings).allSatisfy(f -> {
            assertThat(f).containsEntry("check_code", RecordingCheck.CODE)
                    .containsEntry("target_key", "g-1")
                    .containsEntry("be_snapshot", null)
                    .containsEntry("action_status", "NOT_STARTED")
                    .containsEntry("first_detected_at", firstRunStarted);
            assertThat((String) f.get("mock_snapshot")).contains("\"externalKey\": \"g-1\"");
        });
        assertThat(findings.get(1).get("started_at")).isNotEqualTo(firstRunStarted);
    }

    @Test
    void 상품_하나만_검사하는_관리자_요청은_그_상품의_예약과_Mock_행만_본다() {
        BePreorderRow phone = data.preorder("폰", "t-1", "REGISTERED", "SUCCEEDED", TWO_HOURS_AGO);
        data.preorder("시계", "t-2", "REGISTERED", "SUCCEEDED", TWO_HOURS_AGO);
        MOCK.active("t-0", "R-0", UUID.randomUUID().toString(), data.product("시계").toString(), "SKU", TWO_HOURS_AGO);
        MOCK.active("t-3", "R-3", UUID.randomUUID().toString(), phone.productId().toString(), "SKU", TWO_HOURS_AGO);
        jdbc.sql("""
                        INSERT INTO reconciliation_runs (id, requested_by, product_id, status, created_at, updated_at)
                        VALUES (UUID_TO_BIN(UUID()), 'admin', UUID_TO_BIN(:product), 'REQUESTED', NOW(6), NOW(6))
                        """)
                .param("product", phone.productId().toString()).update();

        service.runRequested();

        assertThat(onlyRun()).containsEntry("status", "COMPLETED")
                .containsEntry("requested_by", "admin")
                .containsEntry("product_id", phone.productId().toString());
        assertThat(check.keys()).containsExactly("t-1", "t-3");
    }

    @Test
    void 불일치가_상한을_넘으면_판정_불가로_끝내고_결과를_남기지_않는다() {
        for (String key : List.of("g-1", "g-2", "g-3")) {
            MOCK.active(key, "R-" + key, UUID.randomUUID().toString(), UUID.randomUUID().toString(), "SKU", TWO_HOURS_AGO);
            check.flag(key);
        }

        service.runScheduled();

        assertThat(onlyRun()).containsEntry("status", "FAILED")
                .containsEntry("error_message", "불일치가 상한(2건)을 넘음 — Mock 주소 · 데이터 설정 확인 필요");
        assertThat(count("reconciliation_findings")).isZero();
    }

    private Map<String, Object> onlyRun() {
        List<Map<String, Object>> runs = jdbc.sql("""
                        SELECT status, requested_by, BIN_TO_UUID(product_id) AS product_id, held_count, pending_sync_count,
                               canceling_count, dead_letter_count, error_message, finished_at,
                               TIMESTAMPDIFF(MINUTE, cutoff_at, started_at) AS cutoff_minutes
                          FROM reconciliation_runs
                        """)
                .query().listOfRows();
        assertThat(runs).hasSize(1);
        return runs.getFirst();
    }

    private long count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
    }
}
