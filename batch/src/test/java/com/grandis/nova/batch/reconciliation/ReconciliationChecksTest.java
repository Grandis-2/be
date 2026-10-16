package com.grandis.nova.batch.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.grandis.nova.batch.support.BatchIntegrationTest;
import com.grandis.nova.batch.support.TestData;
import com.grandis.nova.batch.support.TestData.BePreorderRow;
import com.grandis.nova.common.jpa.StorageClock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.convention.TestBean;

/**
 * 비교 규칙을 실제 DB · 가짜 Mock 원장으로 끝까지 돌린다 — 정상 데이터는 0건, 일부러 어긋낸 데이터는 유형마다 잡는다.
 * 검사 시계를 NOW 로 고정한다. 시험 데이터 시각과 검사 시작이 벌어지면 판정 보류(2분) · 멈춤(45분) 경계를 넘을 수 있다.
 */
class ReconciliationChecksTest extends BatchIntegrationTest {

    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    private static final Instant TWO_HOURS_AGO = NOW.minus(Duration.ofHours(2));
    private static final Instant JUST_NOW = NOW.minusSeconds(5);
    private static final Instant FIVE_MINUTES_AGO = NOW.minus(Duration.ofMinutes(5));
    private static final Instant THIRTY_MINUTES_AGO = NOW.minus(Duration.ofMinutes(30));

    @TestBean(name = "storageClock", methodName = "fixedClock")
    Clock storageClock;

    static Clock fixedClock() {
        return StorageClock.atStorageResolution(Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Autowired
    ReconciliationService service;

    @Test
    void 정상_데이터는_불일치가_없다() {
        mockActive(data.preorder("폰", "n-1", "REGISTERED", "SUCCEEDED", TWO_HOURS_AGO), "폰");
        BePreorderRow reserved = data.preorder("폰", "n-2", "RESERVED", "SUCCEEDED", TWO_HOURS_AGO);
        mockActive(reserved, "폰");
        data.paidOrder(reserved, "SUCCEEDED", TWO_HOURS_AGO);
        // 결제 가능에서 취소 — Mock 은 번호 있는 취소
        BePreorderRow canceled = data.preorder("폰", "n-3", "CANCELED", "SUCCEEDED", TWO_HOURS_AGO);
        data.cancelJob(canceled, "SUCCEEDED", TWO_HOURS_AGO);
        MOCK.put("n-3", canceled.externalNumber(), canceled.customerId().toString(), canceled.productId().toString(),
                TestData.sku("폰"), "CANCELED", TWO_HOURS_AGO, TWO_HOURS_AGO);
        // 등록 대기에서 취소 — 등록 작업은 무효화되고 Mock 에는 취소 표식
        data.cancelJob(data.preorder("폰", "n-4", "CANCELED", "CANCELED", TWO_HOURS_AGO), "SUCCEEDED", TWO_HOURS_AGO);
        MOCK.cancelMarker("n-4", TWO_HOURS_AGO);
        // 관리자 대기(등록 작업 DEAD_LETTER)는 불일치가 아니라 건수다
        data.preorder("폰", "n-5", "PENDING_SYNC", "DEAD_LETTER", TWO_HOURS_AGO);
        // 막 들어온 등록 대기 — 처리 중이라 불일치가 아니고, 발행 전 이벤트 · 결과 없는 차례도 멈춤 기준(45분) 안이라 멈춤이 아니다
        BePreorderRow fresh = data.preorder("시계", "n-6", "PENDING_SYNC", "PENDING", JUST_NOW);
        data.registerJobReady(fresh, 1, JUST_NOW, null);
        data.attempt(fresh, null, JUST_NOW);
        BePreorderRow retried = data.preorder("시계", "n-7", "REGISTERED", "SUCCEEDED", TWO_HOURS_AGO);
        data.registerJobReady(retried, 1, TWO_HOURS_AGO, TWO_HOURS_AGO);
        data.attempt(retried, "TRANSIENT_FAILURE", TWO_HOURS_AGO);
        data.attempt(retried, "SUCCESS", TWO_HOURS_AGO);
        mockActive(retried, "시계");
        // 30분 전에 결제한 결제 가능 — 결제 확인 이벤트가 재전달 중일 수 있어 멈춤 기준(45분) 안 결제는 보지 않는다
        BePreorderRow justPaid = data.preorder("시계", "n-8", "REGISTERED", "SUCCEEDED", TWO_HOURS_AGO);
        mockActive(justPaid, "시계");
        data.paidOrder(justPaid, "SUCCEEDED", THIRTY_MINUTES_AGO);
        // 환불된 결제는 결제 성공이 아니다
        BePreorderRow refunded = data.preorder("시계", "n-9", "REGISTERED", "SUCCEEDED", TWO_HOURS_AGO);
        mockActive(refunded, "시계");
        data.paidOrder(refunded, "REFUNDED", TWO_HOURS_AGO);
        // 검사 도중 Mock 에 등록된 등록 대기 — 판정 보류
        BePreorderRow registering = data.preorder("시계", "n-10", "PENDING_SYNC", "PROCESSING", JUST_NOW);
        MOCK.active("n-10", "R-N10", registering.customerId().toString(), registering.productId().toString(),
                TestData.sku("시계"), JUST_NOW);
        // 5분 전에 결제 가능이 된 예약 — 판정 보류가 아니라 판정해서 일치
        mockActive(data.preorder("시계", "n-11", "REGISTERED", "SUCCEEDED", FIVE_MINUTES_AGO), "시계");
        // 5분 전에 Mock 에서 확정된 예약 — 검사 전 일이라(여유 2분 밖) 판정해서 일치
        BePreorderRow confirmedEarlier = data.preorder("시계", "n-12", "REGISTERED", "SUCCEEDED", TWO_HOURS_AGO);
        MOCK.active("n-12", confirmedEarlier.externalNumber(), confirmedEarlier.customerId().toString(),
                confirmedEarlier.productId().toString(), TestData.sku("시계"), FIVE_MINUTES_AGO);

        service.runScheduled();

        assertThat(run()).containsEntry("status", "COMPLETED")
                .containsEntry("held_count", 1)
                .containsEntry("pending_sync_count", 2)
                .containsEntry("dead_letter_count", 1);
        assertThat(findings()).isEmpty();
    }

    @Test
    void 일부러_어긋낸_데이터는_유형마다_잡는다() {
        // Mock ↔ be
        data.preorder("폰", "a-1", "REGISTERED", "SUCCEEDED", TWO_HOURS_AGO);
        BePreorderRow mockCanceled = data.preorder("폰", "a-2", "RESERVED", "SUCCEEDED", TWO_HOURS_AGO);
        MOCK.put("a-2", mockCanceled.externalNumber(), mockCanceled.customerId().toString(),
                mockCanceled.productId().toString(), TestData.sku("폰"), "CANCELED", TWO_HOURS_AGO, TWO_HOURS_AGO);
        BePreorderRow otherNumber = data.preorder("폰", "a-3", "REGISTERED", "SUCCEEDED", TWO_HOURS_AGO);
        MOCK.active("a-3", "R-OTHER", otherNumber.customerId().toString(), otherNumber.productId().toString(),
                TestData.sku("폰"), TWO_HOURS_AGO);
        BePreorderRow otherSku = data.preorder("폰", "a-4", "REGISTERED", "SUCCEEDED", TWO_HOURS_AGO);
        MOCK.active("a-4", otherSku.externalNumber(), otherSku.customerId().toString(), otherSku.productId().toString(),
                "OTHER-SKU", TWO_HOURS_AGO);
        BePreorderRow canceledButActive = data.preorder("폰", "a-5", "CANCELED", "SUCCEEDED", TWO_HOURS_AGO);
        data.cancelJob(canceledButActive, "SUCCEEDED", TWO_HOURS_AGO);
        mockActive(canceledButActive, "폰");
        MOCK.active("a-6", "R-GHOST", UUID.randomUUID().toString(), data.product("폰").toString(), "SKU", TWO_HOURS_AGO);
        mockActive(data.preorder("폰", "a-7", "PENDING_SYNC", "DEAD_LETTER", TWO_HOURS_AGO), "폰");
        data.preorder("폰", "a-8", "PENDING_SYNC", "DEAD_LETTER", TWO_HOURS_AGO);
        MOCK.cancelMarker("a-8", TWO_HOURS_AGO);
        data.cancelJob(data.preorder("폰", "a-9", "CANCELED", "CANCELED", TWO_HOURS_AGO), "SUCCEEDED", TWO_HOURS_AGO);

        // be 내부 — 예약 한 건으로 보는 것
        mockActive(data.preorder("폰", "b-1", "REGISTERED", "DEAD_LETTER", TWO_HOURS_AGO), "폰");
        BePreorderRow noNumber = data.preorder("폰", "b-2", "REGISTERED", "SUCCEEDED", TWO_HOURS_AGO);
        mockActive(noNumber, "폰");
        setNumber(noNumber, null);
        setNumber(data.preorder("폰", "b-3", "PENDING_SYNC", "DEAD_LETTER", TWO_HOURS_AGO), "R-LATE");
        BePreorderRow cancelUnfinished = data.preorder("폰", "b-4", "CANCELED", "SUCCEEDED", TWO_HOURS_AGO);
        data.cancelJob(cancelUnfinished, "RETRY_SCHEDULED", TWO_HOURS_AGO);
        MOCK.put("b-4", cancelUnfinished.externalNumber(), cancelUnfinished.customerId().toString(),
                cancelUnfinished.productId().toString(), TestData.sku("폰"), "CANCELED", TWO_HOURS_AGO, TWO_HOURS_AGO);
        data.preorder("폰", "b-5", "CANCELING", "SUCCEEDED", TWO_HOURS_AGO);
        data.preorder("폰", "b-6", "PENDING_SYNC", null, TWO_HOURS_AGO);
        BePreorderRow historyBehind = data.preorder("폰", "b-7", "REGISTERED", "SUCCEEDED", TWO_HOURS_AGO);
        mockActive(historyBehind, "폰");
        data.history(historyBehind, "PENDING_SYNC");
        mockActive(data.preorder("배정", "b-8", "REGISTERED", "SUCCEEDED", TWO_HOURS_AGO), "배정");
        jdbc.sql("UPDATE shipment_batches SET position_from = 2 WHERE id = UUID_TO_BIN(:batch)")
                .param("batch", data.batch("배정").toString()).update();

        // be 내부 — SQL 로 보는 것
        BePreorderRow unpublished = data.preorder("폰", "c-1", "REGISTERED", "SUCCEEDED", TWO_HOURS_AGO);
        mockActive(unpublished, "폰");
        data.registerJobReady(unpublished, 1, TWO_HOURS_AGO, null);
        BePreorderRow noResult = data.preorder("폰", "c-2", "REGISTERED", "SUCCEEDED", TWO_HOURS_AGO);
        mockActive(noResult, "폰");
        data.attempt(noResult, null, TWO_HOURS_AGO);
        data.preorder("폰", "c-3", "PENDING_SYNC", "PENDING", TWO_HOURS_AGO);
        mockActive(data.preorder("빈틈", "c-4", "REGISTERED", "SUCCEEDED", TWO_HOURS_AGO), "빈틈");
        BePreorderRow moved = data.preorder("빈틈", "c-5", "REGISTERED", "SUCCEEDED", TWO_HOURS_AGO);
        mockActive(moved, "빈틈");
        jdbc.sql("UPDATE preorders SET queue_position = 5 WHERE id = UUID_TO_BIN(:id)")
                .param("id", moved.id().toString()).update();
        BePreorderRow paidButRegistered = data.preorder("폰", "c-6", "REGISTERED", "SUCCEEDED", TWO_HOURS_AGO);
        mockActive(paidButRegistered, "폰");
        data.paidOrder(paidButRegistered, "SUCCEEDED", TWO_HOURS_AGO);
        // 조사하며 메모만 고친 예약 — 상태는 결제 가능 그대로라 계속 잡는다
        BePreorderRow noteEdited = data.preorder("폰", "c-7", "REGISTERED", "SUCCEEDED", TWO_HOURS_AGO);
        mockActive(noteEdited, "폰");
        data.paidOrder(noteEdited, "SUCCEEDED", TWO_HOURS_AGO);
        jdbc.sql("UPDATE preorders SET internal_note = 'DLQ 확인 중', updated_at = :at WHERE id = UUID_TO_BIN(:id)")
                .param("at", LocalDateTime.ofInstant(JUST_NOW, ZoneOffset.UTC)).param("id", noteEdited.id().toString())
                .update();

        // 5분 전에 바뀐 예약도 판정한다 — 조치 직후 다시 검사해 확인할 수 있다
        data.preorder("폰", "d-1", "REGISTERED", "SUCCEEDED", FIVE_MINUTES_AGO);

        service.runScheduled();

        assertThat(run()).containsEntry("status", "COMPLETED");
        assertThat(findings()).extracting(f -> f.get("check_code"), f -> f.get("target_key")).containsExactlyInAnyOrder(
                tuple("REGISTERED_NOT_IN_MOCK", "a-1"),
                tuple("REGISTERED_NOT_IN_MOCK", "d-1"),
                tuple("PAID_BUT_REGISTERED", "c-7"),
                tuple("REGISTERED_BUT_MOCK_CANCELED", "a-2"),
                tuple("NUMBER_MISMATCH", "a-3"),
                tuple("CONTENT_MISMATCH", "a-4"),
                tuple("CANCELED_BUT_MOCK_ACTIVE", "a-5"),
                tuple("MOCK_ONLY", "a-6"),
                tuple("PENDING_BUT_MOCK_ACTIVE", "a-7"),
                tuple("PENDING_BUT_MOCK_CANCEL_MARKER", "a-8"),
                tuple("CANCELED_WITHOUT_MOCK_MARKER", "a-9"),
                tuple("JOB_STATE_MISMATCH", "b-1"),
                tuple("NUMBER_MISSING", "b-2"),
                tuple("NUMBER_WITHOUT_SUCCESS", "b-3"),
                tuple("CANCEL_JOB_INCOMPLETE", "b-4"),
                tuple("STUCK", "b-5"),
                tuple("OUTBOX_MISSING", "b-6"),
                tuple("STUCK", "b-6"),
                tuple("HISTORY_MISMATCH", "b-7"),
                tuple("SHIPMENT_BATCH_MISMATCH", "b-8"),
                tuple("STUCK", "c-1"),
                tuple("STUCK", "c-2"),
                tuple("OUTBOX_MISSING", "c-3"),
                tuple("STUCK", "c-3"),
                tuple("QUEUE_GAP", data.product("빈틈").toString()),
                tuple("PAID_BUT_REGISTERED", "c-6"));
    }

    @Test
    void SQL_검사도_검사_범위의_상품만_본다() {
        BePreorderRow phone = data.preorder("폰", "p-1", "REGISTERED", "SUCCEEDED", TWO_HOURS_AGO);
        mockActive(phone, "폰");
        data.registerJobReady(phone, 1, TWO_HOURS_AGO, null);
        BePreorderRow watch = data.preorder("시계", "w-1", "REGISTERED", "SUCCEEDED", TWO_HOURS_AGO);
        mockActive(watch, "시계");
        data.registerJobReady(watch, 1, TWO_HOURS_AGO, null);
        data.attempt(watch, null, TWO_HOURS_AGO);
        data.preorder("시계", "w-2", "PENDING_SYNC", "PENDING", TWO_HOURS_AGO);
        BePreorderRow watchPaid = data.preorder("시계", "w-3", "REGISTERED", "SUCCEEDED", TWO_HOURS_AGO);
        mockActive(watchPaid, "시계");
        data.paidOrder(watchPaid, "SUCCEEDED", TWO_HOURS_AGO);
        jdbc.sql("UPDATE preorders SET queue_position = 9 WHERE preorder_token = 'w-1'").update();
        jdbc.sql("""
                        INSERT INTO reconciliation_runs (id, requested_by, product_id, status, created_at, updated_at)
                        VALUES (UUID_TO_BIN(UUID()), 'admin', UUID_TO_BIN(:product), 'REQUESTED', NOW(6), NOW(6))
                        """)
                .param("product", phone.productId().toString()).update();

        service.runRequested();

        assertThat(findings()).extracting(f -> f.get("check_code"), f -> f.get("target_key"))
                .containsExactly(tuple("STUCK", "p-1"));
    }

    @Test
    void SQL_검사가_남긴_값은_그_행의_사실이다() {
        BePreorderRow pending = data.preorder("폰", "c-3", "PENDING_SYNC", "PENDING", TWO_HOURS_AGO);
        // 지난 실행 회차의 이벤트만 있다 — 재처리 뒤 발행 이벤트가 빠진 것
        jdbc.sql("UPDATE preorder_sync_jobs SET run = 2 WHERE id = UUID_TO_BIN(:job)")
                .param("job", pending.registerJobId().toString()).update();
        data.registerJobReady(pending, 1, TWO_HOURS_AGO, TWO_HOURS_AGO);

        service.runScheduled();

        assertThat(findings()).filteredOn(f -> f.get("check_code").equals("OUTBOX_MISSING")).singleElement()
                .satisfies(f -> {
                    assertThat(f).containsEntry("target_key", "c-3").containsEntry("mock_snapshot", null);
                    assertThat((String) f.get("be_snapshot"))
                            .contains("\"syncJobId\": \"" + pending.registerJobId() + "\"")
                            .contains("\"run\": 2");
                });
    }

    private void mockActive(BePreorderRow preorder, String productName) {
        MOCK.active(preorder.token(), preorder.externalNumber(), preorder.customerId().toString(),
                preorder.productId().toString(), TestData.sku(productName), TWO_HOURS_AGO);
    }

    private void setNumber(BePreorderRow preorder, String number) {
        jdbc.sql("UPDATE preorders SET external_reference = :number WHERE id = UUID_TO_BIN(:id)")
                .param("number", number).param("id", preorder.id().toString()).update();
    }

    private Map<String, Object> run() {
        return jdbc.sql("SELECT status, held_count, pending_sync_count, dead_letter_count, error_message"
                        + " FROM reconciliation_runs")
                .query().singleRow();
    }

    private List<Map<String, Object>> findings() {
        return jdbc.sql("SELECT check_code, target_key, be_snapshot, mock_snapshot FROM reconciliation_findings")
                .query().listOfRows();
    }
}
