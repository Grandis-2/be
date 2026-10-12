package com.grandis.nova.batch.reconciliation.check;

import static org.assertj.core.api.Assertions.assertThat;

import com.grandis.nova.batch.reconciliation.mock.MockLedgerEntry;
import com.grandis.nova.batch.reconciliation.run.NewFinding;
import com.grandis.nova.batch.reconciliation.snapshot.BePreorder;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;

/** 여기 오는 예약은 판정 보류가 아니다 — 기준 시각(시작 − 45분) 전부터 바뀌지 않았다 */
class PreorderRowCheckTest {

    private static final Instant AT = Instant.parse("2026-10-12T01:00:00Z");

    private final PreorderRowCheck check = new PreorderRowCheck();

    @ParameterizedTest(name = "{0} · 등록 작업 {1} · 취소 작업 {2}")
    @CsvSource(nullValues = "NONE", value = {
            "REGISTERED,   SUCCEEDED,   NONE",
            "RESERVED,     SUCCEEDED,   NONE",
            "CANCELED,     SUCCEEDED,   SUCCEEDED",
            "CANCELED,     CANCELED,    SUCCEEDED",
            "PENDING_SYNC, DEAD_LETTER, NONE"})
    void 정상_예약은_남기지_않는다(String status, String registerJob, String cancelJob) {
        assertThat(inspect(preorder(status, registerJob, cancelJob))).isEmpty();
    }

    @Test
    void Mock_에만_있는_키는_보지_않는다() {
        MockLedgerEntry ghost = new MockLedgerEntry("k-1", "R-1", "c", "p", "SKU", "ACTIVE", AT, null);
        FindingSink sink = sink();

        check.inspect(new KeyPair("k-1", null, ghost), sink);

        assertThat(sink.findings()).isEmpty();
    }

    @ParameterizedTest(name = "{0} · 등록 작업 {1}")
    @CsvSource({
            "REGISTERED,   DEAD_LETTER",
            "RESERVED,     PENDING",
            "REGISTERED,   CANCELED",
            "CANCELING,    RETRY_SCHEDULED",
            "CANCELED,     DEAD_LETTER"})
    void 예약_상태와_등록_작업_상태가_맞지_않으면_예약_작업_상태_불일치다(String status, String registerJob) {
        assertThat(codes(preorder(status, registerJob, status.equals("CANCELED") ? "SUCCEEDED" : null)))
                .contains("JOB_STATE_MISMATCH");
    }

    @Test
    void 등록_대기의_진행_중_작업은_예약_작업_상태_불일치가_아니다() {
        for (String job : List.of("PENDING", "PROCESSING", "RETRY_SCHEDULED")) {
            assertThat(codes(preorder("PENDING_SYNC", job, null))).doesNotContain("JOB_STATE_MISMATCH");
        }
    }

    @Test
    void 등록_작업이_성공인데_번호가_없으면_번호_누락이다() {
        BePreorder noNumber = new BePreorder("k-1", "id", "REGISTERED", null, "c", "p", "SKU", "SUCCEEDED", null,
                "REGISTERED", 1, 1, null, AT);

        assertThat(codes(noNumber)).containsExactly("NUMBER_MISSING");
    }

    @Test
    void 번호가_있는데_등록_작업이_성공이_아니면_번호_작업_불일치다() {
        BePreorder numbered = new BePreorder("k-1", "id", "PENDING_SYNC", "R-1", "c", "p", "SKU", "DEAD_LETTER", null,
                "PENDING_SYNC", 1, 1, null, AT);

        assertThat(codes(numbered)).containsExactly("NUMBER_WITHOUT_SUCCESS");
    }

    @Test
    void 취소_완료인데_취소_작업이_없거나_성공이_아니면_취소_작업_미완료다() {
        assertThat(codes(preorder("CANCELED", "SUCCEEDED", null))).containsExactly("CANCEL_JOB_INCOMPLETE");
        assertThat(codes(preorder("CANCELED", "SUCCEEDED", "RETRY_SCHEDULED"))).containsExactly("CANCEL_JOB_INCOMPLETE");
    }

    @Test
    void 기준_시간을_넘긴_등록_대기_취소_중은_멈춤이다() {
        assertThat(codes(preorder("PENDING_SYNC", "RETRY_SCHEDULED", null))).containsExactly("STUCK");
        assertThat(codes(preorder("CANCELING", "SUCCEEDED", null))).containsExactly("STUCK");
        assertThat(codes(preorder("CANCELING", "CANCELED", "RETRY_SCHEDULED"))).containsExactly("STUCK");
    }

    @Test
    void 등록_작업이_없으면_발행_누락이다() {
        assertThat(codes(preorder("PENDING_SYNC", null, null))).containsExactly("OUTBOX_MISSING", "STUCK");
    }

    @Test
    void 예약_상태와_마지막_이력의_상태가_다르면_이력_불일치다() {
        BePreorder registeredButHistoryPending = new BePreorder("k-1", "id", "REGISTERED", "R-1", "c", "p", "SKU",
                "SUCCEEDED", null, "PENDING_SYNC", 1, 1, null, AT);
        BePreorder noHistory = new BePreorder("k-1", "id", "REGISTERED", "R-1", "c", "p", "SKU", "SUCCEEDED", null,
                null, 1, 1, null, AT);

        assertThat(codes(registeredButHistoryPending)).containsExactly("HISTORY_MISMATCH");
        assertThat(codes(noHistory)).containsExactly("HISTORY_MISMATCH");
    }

    @ParameterizedTest(name = "순번 {0} · 차수 {1}~{2}")
    @CsvSource(nullValues = "OPEN", value = {"4, 5, 9", "10, 5, 9", "4, 5, OPEN"})
    void 순번이_배송_차수_구간_밖이면_배송_배정_오류다(long position, long from, Long to) {
        BePreorder outside = new BePreorder("k-1", "id", "REGISTERED", "R-1", "c", "p", "SKU", "SUCCEEDED", null,
                "REGISTERED", position, from, to, AT);

        assertThat(codes(outside)).containsExactly("SHIPMENT_BATCH_MISMATCH");
    }

    @ParameterizedTest(name = "순번 {0} · 차수 {1}~{2}")
    @CsvSource(nullValues = "OPEN", value = {"5, 5, 9", "9, 5, 9", "100, 5, OPEN"})
    void 순번이_배송_차수_구간_안이면_배송_배정_오류가_아니다(long position, long from, Long to) {
        BePreorder inside = new BePreorder("k-1", "id", "REGISTERED", "R-1", "c", "p", "SKU", "SUCCEEDED", null,
                "REGISTERED", position, from, to, AT);

        assertThat(codes(inside)).isEmpty();
    }

    @Test
    void be_내부_불일치는_Mock_값을_남기지_않는다() {
        MockLedgerEntry mock = new MockLedgerEntry("k-1", "R-1", "c", "p", "SKU", "ACTIVE", AT, null);
        FindingSink sink = sink();

        check.inspect(new KeyPair("k-1", preorder("CANCELING", "SUCCEEDED", null), mock), sink);

        assertThat(sink.findings()).singleElement().satisfies(f -> {
            assertThat(f.beSnapshot()).contains("\"status\":\"CANCELING\"");
            assertThat(f.mockSnapshot()).isNull();
        });
    }

    private List<String> codes(BePreorder preorder) {
        return inspect(preorder).stream().map(NewFinding::checkCode).toList();
    }

    private List<NewFinding> inspect(BePreorder preorder) {
        FindingSink sink = sink();
        check.inspect(new KeyPair(preorder.token(), preorder, null), sink);
        return sink.findings();
    }

    private static FindingSink sink() {
        return new FindingSink(JsonMapper.builder().build(), 100);
    }

    /** 이력 · 배송 차수가 맞고, 등록 작업이 성공이면 번호가 있는 예약 */
    private static BePreorder preorder(String status, String registerJob, String cancelJob) {
        String number = "SUCCEEDED".equals(registerJob) ? "R-1" : null;
        return new BePreorder("k-1", "id", status, number, "c", "p", "SKU", registerJob, cancelJob, status, 3, 1, 9L, AT);
    }
}
