package com.grandis.nova.preorder.syncjob;

import com.grandis.nova.common.testing.Concurrently.Outcome;
import com.grandis.nova.common.testing.Concurrently;
import com.grandis.nova.preorder.accept.application.AcceptResult;
import com.grandis.nova.preorder.accept.application.PreorderAcceptService;
import com.grandis.nova.preorder.cancel.application.CancelStarter;
import com.grandis.nova.preorder.integration.catalog.CatalogClient;
import com.grandis.nova.preorder.preorder.CancelReason;
import com.grandis.nova.preorder.preorder.EventActor;
import com.grandis.nova.preorder.preorder.PreorderLedger;
import com.grandis.nova.preorder.preorder.Preorders;
import com.grandis.nova.preorder.support.AcceptFixtures;
import com.grandis.nova.preorder.support.PreorderIntegrationTest;
import com.grandis.nova.preorder.support.ShopFixtures;
import com.grandis.nova.preorder.syncjob.application.SyncJobAdminService;
import com.grandis.nova.preorder.syncjob.domain.ReprocessCandidate;
import com.grandis.nova.preorder.syncjob.domain.ReprocessCandidateReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static com.grandis.nova.preorder.support.AccessTokens.admin;
import static com.grandis.nova.preorder.support.AccessTokens.customer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.mockingDetails;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** DB 를 테스트끼리 공유하므로 목록 · 묶음 단정은 이 테스트가 만든 예약 · 고유한 오류 코드로만 한다. */
@PreorderIntegrationTest
@AutoConfigureMockMvc
class AdminSyncJobApiTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    PreorderAcceptService acceptService;

    @Autowired
    CancelStarter cancelStarter;

    @Autowired
    Preorders preorders;

    /** 재처리가 예약 잠금을 기다리는지 보려고 잠금 호출만 가로챈다(실제 동작은 그대로). */
    @MockitoSpyBean
    PreorderLedger ledger;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    ReprocessCandidateReader candidateReader;

    @MockitoBean
    CatalogClient catalogClient;

    ShopFixtures fixtures;
    AcceptFixtures accepts;
    Long preorderId;
    String token;

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
        accepts = new AcceptFixtures(acceptService, fixtures, catalogClient);
        AcceptResult accepted = accepts.accept(fixtures.customer());
        preorderId = accepted.preorder().id();
        token = AcceptFixtures.tokenOf(accepted);
    }

    @Test
    void 목록은_상태_예약으로_거르고_시도_수와_마지막_오류_코드를_준다() throws Exception {
        Long jobId = fixtures.deadLetter(preorderId);
        fixtures.syncAttempt(jobId, 1, "TRANSIENT_FAILURE", 503, "HTTP_503");
        fixtures.syncAttempt(jobId, 2, "REJECTED", 422, "MOCK_REJECTED");

        asAdmin(get("/api/v1/admin/sync-jobs").param("status", "DEAD_LETTER").param("preorderId", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].syncJobId").value(jobId))
                .andExpect(jsonPath("$.data.items[0].preorderId").value(token))
                .andExpect(jsonPath("$.data.items[0].jobType").value("REGISTER"))
                .andExpect(jsonPath("$.data.items[0].attemptCount").value(2))
                .andExpect(jsonPath("$.data.items[0].lastErrorCode").value("MOCK_REJECTED"))
                .andExpect(jsonPath("$.data.items[0].deadLetteredAt").exists())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.errorGroups").value(nullValue()));
        asAdmin(get("/api/v1/admin/sync-jobs").param("status", "PENDING").param("preorderId", token))
                .andExpect(jsonPath("$.data.items", hasSize(0)));
        asAdmin(get("/api/v1/admin/sync-jobs").param("preorderId", ShopFixtures.unique()))
                .andExpect(jsonPath("$.data.items", hasSize(0)))
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void groupByError_면_마지막_시도의_오류_코드로_묶은_건수를_준다() throws Exception {
        String code = "E-" + ShopFixtures.unique();
        for (int i = 0; i < 2; i++) {
            Long jobId = fixtures.deadLetter(accepts.accept(fixtures.customer()).preorder().id());
            fixtures.syncAttempt(jobId, 1, "TRANSIENT_FAILURE", 503, "HTTP_503");
            fixtures.syncAttempt(jobId, 2, "REJECTED", 422, code);
        }

        asAdmin(get("/api/v1/admin/sync-jobs").param("status", "DEAD_LETTER").param("groupByError", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.errorGroups[?(@.errorCode == '%s')].count".formatted(code), contains(2)));
    }

    @Test
    void 상세는_요청_본문을_객체로_시도_기록과_함께_준다() throws Exception {
        Long jobId = fixtures.deadLetter(preorderId);
        fixtures.syncAttempt(jobId, 1, "REJECTED", 422, "MOCK_REJECTED");

        asAdmin(get("/api/v1/admin/sync-jobs/{id}", jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.syncJobId").value(jobId))
                .andExpect(jsonPath("$.data.requestPayload.ourReservationId").value(token))
                .andExpect(jsonPath("$.data.attempts", hasSize(1)))
                .andExpect(jsonPath("$.data.attempts[0].errorCode").value("MOCK_REJECTED"));
        asAdmin(get("/api/v1/admin/sync-jobs/{id}", Long.MAX_VALUE))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SYNC_JOB_NOT_FOUND"));
    }

    @Test
    void DEAD_LETTER_인_등록_작업은_202_로_재처리_요청을_남긴다() throws Exception {
        Long jobId = fixtures.deadLetter(preorderId);

        asAdmin(post("/api/v1/admin/sync-jobs/{id}/reprocess", jobId))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.syncJobId").value(jobId))
                .andExpect(jsonPath("$.data.status").value("DEAD_LETTER"));

        assertThat(reprocessRequests(jobId)).containsExactly(Map.of("syncJobId", jobId, "requestedBy", "admin"));
    }

    /** 요청을 받아들일 때마다 한 건씩 남긴다(중복 제거는 하지 않는다). worker 가 DEAD_LETTER 일 때만 되돌려 효과는 한 번이다. */
    @Test
    void 같은_작업을_연달아_재처리하면_받아들인_요청마다_한_건씩_남긴다() throws Exception {
        Long jobId = fixtures.deadLetter(preorderId);

        asAdmin(post("/api/v1/admin/sync-jobs/{id}/reprocess", jobId)).andExpect(status().isAccepted());
        asAdmin(post("/api/v1/admin/sync-jobs/{id}/reprocess", jobId)).andExpect(status().isAccepted());

        assertThat(reprocessRequests(jobId)).hasSize(2);
    }

    @Test
    void 같은_작업을_동시에_재처리해도_둘_다_받아들이고_한_건씩_남긴다() throws Exception {
        Long jobId = fixtures.deadLetter(preorderId);

        List<Outcome<Integer>> outcomes = Concurrently.run(2, i -> () ->
                asAdmin(post("/api/v1/admin/sync-jobs/{id}/reprocess", jobId)).andReturn().getResponse().getStatus());

        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome.value()).isEqualTo(202));
        assertThat(reprocessRequests(jobId)).hasSize(2);
    }

    @Test
    void 재처리_조건_밖이면_409_와_사유() throws Exception {
        Long jobId = jdbcTemplate.queryForObject(
                "SELECT id FROM preorder_sync_jobs WHERE preorder_id = ? AND job_type = 'REGISTER'", Long.class,
                preorderId);

        asAdmin(post("/api/v1/admin/sync-jobs/{id}/reprocess", jobId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SYNC_JOB_NOT_REPROCESSABLE"))
                .andExpect(jsonPath("$.error.details.reason").value("jobType=REGISTER, status=PENDING"));

        cancelStarter.start(preorders.findById(preorderId).orElseThrow(), EventActor.USER, null, CancelReason.USER);
        fixtures.deadLetter(preorderId);
        asAdmin(post("/api/v1/admin/sync-jobs/{id}/reprocess", jobId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.details.reason").value("preorderStatus=CANCELING"));

        assertThat(reprocessRequests(jobId)).isEmpty();
    }

    /** 취소가 예약을 잠근 동안 재처리는 기다렸다가, 취소가 커밋된 뒤의 상태로 판정한다. */
    @Test
    void 취소_트랜잭션이_예약을_잠근_동안_재처리는_기다렸다가_409() throws Exception {
        Long jobId = fixtures.deadLetter(preorderId);
        CountDownLatch cancelLocked = new CountDownLatch(1);
        CountDownLatch reprocessWaiting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> canceling = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                cancelStarter.start(preorders.findById(preorderId).orElseThrow(), EventActor.USER, null,
                        CancelReason.USER);
                cancelLocked.countDown();
                awaitQuietly(release);
            }));
            Future<MockHttpServletResponse> reprocessing;
            try {
                assertThat(cancelLocked.await(10, TimeUnit.SECONDS)).isTrue();
                // 취소는 이미 예약을 잠갔으므로 이 뒤의 잠금 조회는 재처리의 것이다
                PreorderLedger target = AopTestUtils.getUltimateTargetObject(ledger);
                Answer<?> delegate = mockingDetails(target).getMockCreationSettings().getDefaultAnswer();
                willAnswer(invocation -> {
                    reprocessWaiting.countDown();
                    return delegate.answer(invocation);
                }).given(target).lockStatus(any());
                reprocessing = executor.submit(() -> asAdmin(post("/api/v1/admin/sync-jobs/{id}/reprocess", jobId))
                        .andReturn().getResponse());
                assertThat(reprocessWaiting.await(10, TimeUnit.SECONDS)).isTrue();
                await().alias("취소가 예약을 잠근 동안 재처리는 끝나지 않는다")
                        .during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(5))
                        .until(() -> !reprocessing.isDone());
            } finally {
                release.countDown();
            }

            canceling.get(10, TimeUnit.SECONDS);
            MockHttpServletResponse response = reprocessing.get(10, TimeUnit.SECONDS);
            assertThat(response.getStatus()).isEqualTo(409);
            assertThat(response.getContentAsString()).as("취소가 커밋한 작업 상태를 보고 판정했다")
                    .contains("jobType=REGISTER, status=CANCELED");
        }
        assertThat(reprocessRequests(jobId)).isEmpty();
    }

    @Test
    void 관리자가_아니면_403_토큰이_없으면_401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/sync-jobs").with(customer(1L)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/admin/sync-jobs/{id}/reprocess", 1L))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 일괄_재처리는_대상과_건너뛴_수를_바로_주고_요청을_이어서_남긴다() throws Exception {
        Long first = fixtures.deadLetter(preorderId);
        Long second = fixtures.deadLetter(accepts.accept(fixtures.customer()).preorder().id());
        Long pending = jdbcTemplate.queryForObject(
                "SELECT id FROM preorder_sync_jobs WHERE preorder_id = ? AND job_type = 'REGISTER'", Long.class,
                accepts.accept(fixtures.customer()).preorder().id());

        String body = "{\"syncJobIds\":[%d,%d,%d,%d],\"ratePerSecond\":200}"
                .formatted(first, second, pending, Long.MAX_VALUE);
        batch(body)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.targetCount").value(2))
                .andExpect(jsonPath("$.data.skippedCount").value(2))
                .andExpect(jsonPath("$.data.estimatedSeconds").value(1));

        await().atMost(Duration.ofSeconds(5))
                .until(() -> reprocessRequests(first).size() == 1 && reprocessRequests(second).size() == 1);
        assertThat(reprocessRequests(pending)).isEmpty();
    }

    @Test
    void 일괄_재처리를_오류_코드로_거른다() throws Exception {
        String code = "E-" + ShopFixtures.unique();
        Long matching = fixtures.deadLetter(preorderId);
        fixtures.syncAttempt(matching, 1, "REJECTED", 422, code);
        Long other = fixtures.deadLetter(accepts.accept(fixtures.customer()).preorder().id());
        fixtures.syncAttempt(other, 1, "REJECTED", 422, "OTHER");

        batch("{\"errorCodeFilter\":\"%s\"}".formatted(code))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.targetCount").value(1))
                .andExpect(jsonPath("$.data.skippedCount").value(0));

        await().atMost(Duration.ofSeconds(5)).until(() -> reprocessRequests(matching).size() == 1);
        assertThat(reprocessRequests(other)).isEmpty();
    }

    @Test
    void 초당_건수는_1에서_200_사이이고_작업은_한_번에_최대_1000_건이다() throws Exception {
        batch("{\"ratePerSecond\":0}").andExpect(status().isBadRequest());
        batch("{\"ratePerSecond\":201}").andExpect(status().isBadRequest());
        String tooMany = LongStream.rangeClosed(1, SyncJobAdminService.MAX_BATCH_SIZE + 1)
                .mapToObj(String::valueOf)
                .collect(Collectors.joining(",", "{\"syncJobIds\":[", "]}"));
        batch(tooMany).andExpect(status().isBadRequest());
    }

    @Test
    void 전체_대상은_id_순으로_상한만큼만_읽는다() {
        String code = "E-" + ShopFixtures.unique();
        Long first = fixtures.deadLetter(preorderId);
        fixtures.syncAttempt(first, 1, "REJECTED", 422, code);
        Long second = fixtures.deadLetter(accepts.accept(fixtures.customer()).preorder().id());
        fixtures.syncAttempt(second, 1, "REJECTED", 422, code);

        assertThat(candidateReader.findDeadLetters(code, 1))
                .extracting(ReprocessCandidate::syncJobId)
                .containsExactly(first);
    }

    @Test
    void 전체_대상에서_취소_중인_예약의_작업은_상한을_차지하지_않는다() {
        String code = "E-" + ShopFixtures.unique();
        cancelStarter.start(preorders.findById(preorderId).orElseThrow(), EventActor.USER, null, CancelReason.USER);
        Long canceling = fixtures.deadLetter(preorderId);
        fixtures.syncAttempt(canceling, 1, "REJECTED", 422, code);
        Long reprocessable = fixtures.deadLetter(accepts.accept(fixtures.customer()).preorder().id());
        fixtures.syncAttempt(reprocessable, 1, "REJECTED", 422, code);

        assertThat(candidateReader.findDeadLetters(code, 1))
                .extracting(ReprocessCandidate::syncJobId)
                .containsExactly(reprocessable);
    }

    private ResultActions batch(String body) throws Exception {
        return asAdmin(post("/api/v1/admin/sync-jobs/reprocess-batch")
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions asAdmin(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.with(admin()));
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private List<Map<String, Object>> reprocessRequests(Long syncJobId) {
        return jdbcTemplate.queryForList("""
                SELECT CAST(JSON_EXTRACT(payload, '$.syncJobId') AS UNSIGNED) AS syncJobId,
                       JSON_UNQUOTE(JSON_EXTRACT(payload, '$.requestedBy')) AS requestedBy
                  FROM outbox_events WHERE event_type = 'SYNC_JOB_REPROCESS_REQUESTED' AND aggregate_id = ?
                """, syncJobId).stream()
                .map(row -> Map.<String, Object>of("syncJobId", ((Number) row.get("syncJobId")).longValue(),
                        "requestedBy", row.get("requestedBy")))
                .toList();
    }
}
