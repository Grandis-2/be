package com.grandis.nova.preorder.event;

import com.grandis.nova.common.UuidBinary;
import com.grandis.nova.preorder.accept.application.AcceptResult;
import com.grandis.nova.preorder.accept.application.PreorderAcceptService;
import com.grandis.nova.preorder.cancel.application.CancelStarter;
import com.grandis.nova.preorder.integration.catalog.CatalogClient;
import com.grandis.nova.preorder.preorder.CancelReason;
import com.grandis.nova.preorder.preorder.EventActor;
import com.grandis.nova.preorder.preorder.Preorders;
import com.grandis.nova.preorder.support.AcceptFixtures;
import com.grandis.nova.preorder.support.PreorderIntegrationTest;
import com.grandis.nova.preorder.support.ShopFixtures;
import com.grandis.nova.preorder.syncjob.NewSyncAttempt;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * worker 가 보낸 결과 이벤트(성공 · 실패한 차례 · 포기)를 작업 원장과 시도 기록에 남기는지 본다.
 * 이벤트는 순서 없이 · 두 번 올 수 있고, 취소 · 관리자 재처리와 엇갈릴 수 있다.
 */
@PreorderIntegrationTest
class ExternalJobResultTest {

    static final Instant STARTED = Instant.parse("2026-10-04T01:00:00.123456Z");
    static final Instant FINISHED = Instant.parse("2026-10-04T01:00:01.654321Z");

    @Autowired
    PreorderEventHandler handler;

    @Autowired
    CancelStarter cancelStarter;

    @Autowired
    PreorderAcceptService acceptService;

    @Autowired
    Preorders preorders;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    MeterRegistry meterRegistry;

    @MockitoBean
    CatalogClient catalogClient;

    ShopFixtures fixtures;
    UUID preorderId;
    String token;
    UUID jobId;

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
        AcceptResult accepted = new AcceptFixtures(acceptService, fixtures, catalogClient).accept(fixtures.customer());
        preorderId = accepted.preorder().id();
        token = AcceptFixtures.tokenOf(accepted);
        jobId = fixtures.registerJob(preorderId);
    }

    @Test
    void 성공_이벤트로_작업을_성공으로_바꾸고_예약에_반영하며_차례를_남긴다() {
        String eventId = ShopFixtures.unique();
        ExternalJobSucceeded message = new ExternalJobSucceeded(jobId, token, "REGISTER", external(),
                attempt(1, 1, "SUCCESS", 201, null));

        handler.onExternalJobSucceeded(eventId, message);
        handler.onExternalJobSucceeded(eventId, message);

        assertThat(jobStatus()).isEqualTo("SUCCEEDED");
        assertThat(preorderStatus()).isEqualTo("REGISTERED");
        assertThat(attempts()).as("같은 이벤트를 두 번 받아도 한 줄").hasSize(1);
        assertThat(attempts().getFirst())
                .containsEntry("attempt_number", 1).containsEntry("generation", 1)
                .containsEntry("generation_attempt", 1).containsEntry("actor", "SYSTEM")
                .containsEntry("result", "SUCCESS").containsEntry("http_status", 201)
                .containsEntry("started_at", "2026-10-04 01:00:00.123456")
                .containsEntry("finished_at", "2026-10-04 01:00:01.654321").containsEntry("event_id", eventId);
    }

    @Test
    void 실패한_차례는_받은_순서로_번호를_매겨_남기고_작업을_재시도_대기로_둔다() {
        String first = ShopFixtures.unique();
        handler.onExternalJobAttempted(first, attempted(attempt(1, 1, "TRANSIENT_FAILURE", 503, "UPSTREAM_UNAVAILABLE")));
        handler.onExternalJobAttempted(first, attempted(attempt(1, 1, "TRANSIENT_FAILURE", 503, "UPSTREAM_UNAVAILABLE")));
        handler.onExternalJobAttempted(ShopFixtures.unique(), attempted(attempt(1, 2, "UNKNOWN", null, null)));
        handler.onExternalJobAttempted(ShopFixtures.unique(), attempted(attempt(2, 1, "TRANSIENT_FAILURE", 429, null)));

        assertThat(jobStatus()).isEqualTo("RETRY_SCHEDULED");
        assertThat(preorderStatus()).isEqualTo("PENDING_SYNC");
        assertThat(attempts()).extracting(row -> row.get("attempt_number") + ":" + row.get("generation") + "-"
                        + row.get("generation_attempt") + ":" + row.get("result"))
                .containsExactly("1:1-1:TRANSIENT_FAILURE", "2:1-2:UNKNOWN", "3:2-1:TRANSIENT_FAILURE");
    }

    /** 외부가 준 값은 길이를 믿지 않는다 — 칸보다 길어도 메시지를 버리지 않고 잘라 남긴다. */
    @Test
    void 칸보다_긴_오류_코드와_메시지는_잘라_남긴다() {
        NewSyncAttempt tooLong = new NewSyncAttempt(1, 1, "SYSTEM", "REJECTED", 422, "C".repeat(150),
                "가".repeat(499) + "😀😀", STARTED, FINISHED);

        handler.onExternalJobAttempted(ShopFixtures.unique(), attempted(tooLong));

        Map<String, Object> row = attempts().getFirst();
        assertThat((String) row.get("error_code")).hasSize(100);
        assertThat((String) row.get("error_message")).as("글자 500개 — 이모지를 반으로 자르지 않는다")
                .isEqualTo("가".repeat(499) + "😀");
    }

    @Test
    void 포기_이벤트로_등록_작업을_DEAD_LETTER_로_두고_예약은_그대로_둔다() {
        String eventId = ShopFixtures.unique();
        String reason = "R-" + ShopFixtures.unique();
        ExternalJobFailed failed = failed("REGISTER", reason, attempt(1, 1, "REJECTED", 422, "MOCK_REJECTED"));

        handler.onExternalJobFailed(eventId, failed);
        handler.onExternalJobFailed(eventId, failed);

        assertThat(jobRow()).containsEntry("status", "DEAD_LETTER");
        assertThat(jobRow().get("dead_lettered_at")).isNotNull();
        assertThat(preorderStatus()).isEqualTo("PENDING_SYNC");
        assertThat(attempts()).hasSize(1);
        assertThat(failedCount("REGISTER", reason)).as("같은 이벤트를 두 번 받아도 한 번만 센다").isEqualTo(1);
    }

    /** 호출하지 않고 끝낸 포기(재시도 소진 등)는 차례 기록 없이 온다. */
    @Test
    void 차례_기록_없는_포기_이벤트도_작업을_DEAD_LETTER_로_둔다() {
        handler.onExternalJobFailed(ShopFixtures.unique(), failed("REGISTER", "GENERATION_EXHAUSTED", null));

        assertThat(jobStatus()).isEqualTo("DEAD_LETTER");
        assertThat(attempts()).isEmpty();
    }

    @Test
    void 포기한_뒤_늦게_온_실패한_차례는_기록만_하고_작업을_되돌리지_않는다() {
        handler.onExternalJobFailed(ShopFixtures.unique(), failed("REGISTER", "GENERATION_EXHAUSTED", null));

        handler.onExternalJobAttempted(ShopFixtures.unique(), attempted(attempt(3, 4, "TRANSIENT_FAILURE", 503, null)));

        assertThat(jobStatus()).isEqualTo("DEAD_LETTER");
        assertThat(attempts()).hasSize(1);
    }

    /** 응답을 못 받아 포기했지만 외부에는 등록된 경우 — 늦게라도 성공을 알면 예약을 살린다. */
    @Test
    void 포기한_뒤_늦게_온_성공은_받아_작업과_예약에_반영한다() {
        handler.onExternalJobFailed(ShopFixtures.unique(), failed("REGISTER", "GENERATION_EXHAUSTED", null));

        handler.onExternalJobSucceeded(ShopFixtures.unique(), ShopFixtures.succeeded(jobId, token, "REGISTER", external()));

        assertThat(jobRow()).containsEntry("status", "SUCCEEDED").containsEntry("dead_lettered_at", null);
        assertThat(preorderStatus()).isEqualTo("REGISTERED");
    }

    @Test
    void 성공한_작업은_늦게_온_실패한_차례나_포기로_되돌아가지_않는다() {
        handler.onExternalJobSucceeded(ShopFixtures.unique(), ShopFixtures.succeeded(jobId, token, "REGISTER", external()));

        handler.onExternalJobAttempted(ShopFixtures.unique(), attempted(attempt(1, 1, "UNKNOWN", null, null)));
        handler.onExternalJobFailed(ShopFixtures.unique(), failed("REGISTER", "GENERATION_EXHAUSTED", null));

        assertThat(jobStatus()).isEqualTo("SUCCEEDED");
        assertThat(preorderStatus()).isEqualTo("REGISTERED");
    }

    @Test
    void 취소가_무효화한_등록_작업은_어떤_결과가_와도_그대로다() {
        cancelStarter.start(preorders.findById(preorderId).orElseThrow(), EventActor.USER, null, CancelReason.USER);

        handler.onExternalJobAttempted(ShopFixtures.unique(), attempted(attempt(1, 1, "UNKNOWN", null, null)));
        handler.onExternalJobFailed(ShopFixtures.unique(), failed("REGISTER", "GENERATION_EXHAUSTED", null));
        handler.onExternalJobSucceeded(ShopFixtures.unique(), new ExternalJobSucceeded(jobId, token, "REGISTER", external(),
                attempt(1, 2, "SUCCESS", 201, null)));

        assertThat(jobStatus()).isEqualTo("CANCELED");
        assertThat(preorderStatus()).isEqualTo("CANCELING");
        assertThat(attempts()).as("외부 호출이 있었다는 기록은 남긴다").hasSize(2);
    }

    /** SQS 는 같은 메시지를 한참 뒤에 다시 줄 수 있다 — 재처리로 되살린 작업을 옛 포기가 다시 죽이면 안 된다. */
    @Test
    void 재처리로_되돌린_뒤_같은_포기_이벤트를_다시_받아도_작업은_그대로다() {
        String eventId = ShopFixtures.unique();
        ExternalJobFailed failed = failed("REGISTER", "MOCK_REJECTED", attempt(1, 1, "REJECTED", 422, "MOCK_REJECTED"));
        handler.onExternalJobFailed(eventId, failed);
        reopen();

        handler.onExternalJobFailed(eventId, failed);

        assertThat(jobStatus()).isEqualTo("PENDING");
        assertThat(attempts()).hasSize(1);
    }

    /** 재처리하면 worker 는 세대 · 차례를 처음부터 다시 센다. 이전 기록과 겹쳐도 새 줄로 남아야 한다. */
    @Test
    void 재처리_뒤의_차례는_세대와_차례가_이전과_같아도_새로_남긴다() {
        handler.onExternalJobFailed(ShopFixtures.unique(), failed("REGISTER", "MOCK_REJECTED",
                attempt(1, 1, "REJECTED", 422, "MOCK_REJECTED")));
        reopen();

        handler.onExternalJobSucceeded(ShopFixtures.unique(), new ExternalJobSucceeded(jobId, token, "REGISTER", external(),
                new NewSyncAttempt(1, 1, "ADMIN", "SUCCESS", 201, null, null, STARTED, FINISHED)));

        assertThat(attempts()).extracting(row -> row.get("attempt_number") + ":" + row.get("generation") + "-"
                        + row.get("generation_attempt") + ":" + row.get("actor"))
                .containsExactly("1:1-1:SYSTEM", "2:1-1:ADMIN");
        assertThat(jobStatus()).isEqualTo("SUCCEEDED");
    }

    /** 취소 작업은 포기 상태가 없다(DB CHECK). 외부에 예약이 남으므로 지표와 로그로 알린다. */
    @Test
    void 취소_작업의_포기는_작업을_바꾸지_않고_지표로_알린다() {
        handler.onExternalJobSucceeded(ShopFixtures.unique(), ShopFixtures.succeeded(jobId, token, "REGISTER", external()));
        cancelStarter.start(preorders.findById(preorderId).orElseThrow(), EventActor.USER, null, CancelReason.USER);
        handler.onOrderSettled(new PreorderOrderSettled(token, PreorderOrderSettled.Result.NO_ORDER, null,
                fixtures.cancelSequence(preorderId), null));
        UUID cancelJobId = jdbcTemplate.queryForObject(
                "SELECT id FROM preorder_sync_jobs WHERE preorder_id = ? AND job_type = 'CANCEL'",
                (rs, rowNum) -> UuidBinary.fromBytes(rs.getBytes(1)), (Object) UuidBinary.toBytes(preorderId));
        String reason = "R-" + ShopFixtures.unique();

        handler.onExternalJobFailed(ShopFixtures.unique(), new ExternalJobFailed(cancelJobId, token, "CANCEL", reason,
                attempt(1, 1, "TRANSIENT_FAILURE", 503, null)));

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM preorder_sync_jobs WHERE id = ?", String.class,
                (Object) UuidBinary.toBytes(cancelJobId))).isEqualTo("PENDING");
        assertThat(preorderStatus()).isEqualTo("CANCELING");
        assertThat(failedCount("CANCEL", reason)).isEqualTo(1);
    }

    /** 다시 받아도 생기지 않는 작업이다 — 재시도 · DLQ 로 보내지 않고 버린다. */
    @Test
    void 모르는_작업의_결과는_예외_없이_버린다() {
        UUID unknown = UUID.randomUUID();

        assertThatCode(() -> {
            handler.onExternalJobSucceeded(ShopFixtures.unique(), ShopFixtures.succeeded(unknown, token, "REGISTER", external()));
            handler.onExternalJobAttempted(ShopFixtures.unique(), new ExternalJobAttempted(unknown, token, "REGISTER",
                    attempt(1, 1, "UNKNOWN", null, null)));
            handler.onExternalJobFailed(ShopFixtures.unique(), new ExternalJobFailed(unknown, token, "REGISTER", "X", null));
        }).doesNotThrowAnyException();
        assertThat(preorderStatus()).isEqualTo("PENDING_SYNC");
    }

    /** 외부 예약 번호는 예약마다 달라야 한다(DB 유니크). */
    private static String external() {
        return "R-" + ShopFixtures.unique();
    }

    private static NewSyncAttempt attempt(int generation, int attempt, String result, Integer httpStatus,
                                          String errorCode) {
        return new NewSyncAttempt(generation, attempt, "SYSTEM", result, httpStatus, errorCode, null, STARTED, FINISHED);
    }

    private ExternalJobAttempted attempted(NewSyncAttempt attempt) {
        return new ExternalJobAttempted(jobId, token, "REGISTER", attempt);
    }

    private ExternalJobFailed failed(String jobType, String reason, NewSyncAttempt attempt) {
        return new ExternalJobFailed(jobId, token, jobType, reason, attempt);
    }

    /** 관리자 재처리가 작업을 되돌린 것처럼 만든다. */
    private void reopen() {
        jdbcTemplate.update("UPDATE preorder_sync_jobs SET status = 'PENDING', dead_lettered_at = NULL WHERE id = ?",
                (Object) UuidBinary.toBytes(jobId));
    }

    private double failedCount(String jobType, String reason) {
        return meterRegistry.counter(PreorderEventHandler.FAILED_METRIC, "jobType", jobType, "reason", reason).count();
    }

    private String jobStatus() {
        return (String) jobRow().get("status");
    }

    private Map<String, Object> jobRow() {
        return jdbcTemplate.queryForMap("SELECT status, dead_lettered_at FROM preorder_sync_jobs WHERE id = ?",
                (Object) UuidBinary.toBytes(jobId));
    }

    private String preorderStatus() {
        return jdbcTemplate.queryForObject("SELECT status FROM preorders WHERE id = ?", String.class,
                (Object) UuidBinary.toBytes(preorderId));
    }

    private List<Map<String, Object>> attempts() {
        return jdbcTemplate.queryForList("""
                SELECT attempt_number, generation, generation_attempt, actor, result, http_status, error_code,
                       error_message, CAST(started_at AS CHAR) AS started_at, CAST(finished_at AS CHAR) AS finished_at,
                       event_id
                  FROM preorder_sync_attempts WHERE sync_job_id = ? ORDER BY attempt_number
                """, (Object) UuidBinary.toBytes(jobId));
    }
}
