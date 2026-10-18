package com.grandis.nova.preorder.event;

import com.grandis.nova.common.UuidBinary;
import com.grandis.nova.preorder.accept.application.AcceptResult;
import com.grandis.nova.preorder.accept.application.PreorderAcceptService;
import com.grandis.nova.preorder.cancel.application.CancelStarter;
import com.grandis.nova.preorder.integration.catalog.CatalogClient;
import com.grandis.nova.preorder.preorder.CancelReason;
import com.grandis.nova.preorder.preorder.EventActor;
import com.grandis.nova.preorder.preorder.PreorderHistoryEntry;
import com.grandis.nova.preorder.preorder.PreorderLedger;
import com.grandis.nova.preorder.preorder.PreorderStatus;
import com.grandis.nova.preorder.preorder.Preorders;
import com.grandis.nova.preorder.support.AcceptFixtures;
import com.grandis.nova.preorder.support.PreorderIntegrationTest;
import com.grandis.nova.preorder.support.ShopFixtures;
import com.grandis.nova.preorder.syncjob.NewSyncAttempt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.grandis.nova.preorder.support.AccessTokens.admin;
import static com.grandis.nova.preorder.support.AccessTokens.customer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.mockingDetails;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * worker 가 외부 취소를 포기한 뒤. 작업은 DEAD_LETTER, 예약은 CANCELING 에 멈추고, 관리자가 재처리하거나 외부에서 확인하고 끝낸다.
 * order · worker 는 대역이고 그 결과를 이벤트 처리기에 직접 넣는다.
 */
@PreorderIntegrationTest
@AutoConfigureMockMvc
class CancelJobGiveUpTest {

    static final Instant STARTED = Instant.parse("2026-10-04T01:00:00Z");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    PreorderEventHandler handler;

    @Autowired
    CancelStarter cancelStarter;

    @Autowired
    PreorderAcceptService acceptService;

    @Autowired
    Preorders preorders;

    /** 두 경로가 같은 예약 잠금에서 겹치는지 보려고 잠금 호출만 가로챈다(실제 동작은 그대로). */
    @MockitoSpyBean
    PreorderLedger ledger;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @MockitoBean
    CatalogClient catalogClient;

    ShopFixtures fixtures;
    AcceptFixtures accepts;
    UUID preorderId;
    String token;
    UUID cancelJobId;

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
        accepts = new AcceptFixtures(acceptService, fixtures, catalogClient);
        Booking booking = cancelRequested();
        preorderId = booking.preorderId();
        token = booking.token();
        cancelJobId = booking.cancelJobId();
    }

    @Test
    void 외부_취소를_포기하면_작업은_DEAD_LETTER_예약은_취소_중으로_남는다() {
        giveUp(cancelJobId, token, 1);

        assertThat(job(cancelJobId)).containsEntry("status", "DEAD_LETTER").containsEntry("run", 1);
        assertThat(job(cancelJobId).get("dead_lettered_at")).isNotNull();
        assertThat(preorderStatus()).isEqualTo(PreorderStatus.CANCELING);
    }

    /** 포기는 worker 의 판단일 뿐이다 — 외부에서 늦게 끝났다는 결과가 오면 그대로 받는다. */
    @Test
    void 포기한_뒤_늦게_온_외부_취소_성공으로_취소가_끝난다() {
        giveUp(cancelJobId, token, 1);

        handler.onExternalJobSucceeded(ShopFixtures.unique(),
                ShopFixtures.succeeded(cancelJobId, token, "CANCEL", null));

        assertThat(job(cancelJobId)).containsEntry("status", "SUCCEEDED").containsEntry("dead_lettered_at", null);
        assertThat(preorderStatus()).isEqualTo(PreorderStatus.CANCELED);
        assertThat(canceledNotifications()).isEqualTo(1);
    }

    @Test
    void 재처리하면_실행_회차를_올려_취소_메시지를_다시_보내고_그_성공으로_취소가_끝난다() throws Exception {
        giveUp(cancelJobId, token, 1);

        asAdmin(post("/api/v1/admin/preorders/sync-jobs/{id}/reprocess", cancelJobId))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.jobType").value("CANCEL"))
                .andExpect(jsonPath("$.data.status").value("PENDING"));

        assertThat(job(cancelJobId)).containsEntry("status", "PENDING").containsEntry("run", 2);
        assertThat(cancelRequests(cancelJobId)).containsExactly(Map.of("actor", "SYSTEM", "run", 1L),
                Map.of("actor", "ADMIN", "run", 2L));

        giveUp(cancelJobId, token, 1);
        assertThat(job(cancelJobId)).as("지난 실행의 포기는 되돌린 작업을 바꾸지 않는다").containsEntry("status", "PENDING");

        handler.onExternalJobSucceeded(ShopFixtures.unique(),
                ShopFixtures.succeeded(cancelJobId, token, "CANCEL", null));
        assertThat(job(cancelJobId)).containsEntry("status", "SUCCEEDED");
        assertThat(preorderStatus()).isEqualTo(PreorderStatus.CANCELED);
    }

    @Test
    void 관리자가_끝내면_작업은_성공_예약은_취소_완료이고_이력에_관리자와_사유가_남고_알림은_한_건이다() throws Exception {
        giveUp(cancelJobId, token, 1);

        resolve(cancelJobId, "외부 콘솔에서 취소 확인")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.preorderId").value(token))
                .andExpect(jsonPath("$.data.status").value("CANCELED"));

        assertThat(job(cancelJobId)).containsEntry("status", "SUCCEEDED").containsEntry("dead_lettered_at", null);
        assertThat(preorderStatus()).isEqualTo(PreorderStatus.CANCELED);
        PreorderHistoryEntry last = preorders.history(preorderId).getLast();
        assertThat(last.toStatus()).isEqualTo(PreorderStatus.CANCELED);
        assertThat(last.actor()).isEqualTo(EventActor.ADMIN);
        assertThat(last.reason()).isEqualTo("외부 콘솔에서 취소 확인");
        assertThat(canceledNotifications()).isEqualTo(1);

        resolve(cancelJobId, "외부 콘솔에서 취소 확인")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SYNC_JOB_NOT_RESOLVABLE"));
        assertThat(canceledNotifications()).as("다시 보내도 알림은 그대로").isEqualTo(1);
    }

    /** 먼저 잠근 쪽이 커밋하기 전에 다른 쪽이 같은 예약 잠금을 기다린다. 어느 쪽이 먼저든 CANCELED 는 한 번, 알림은 한 건이다. */
    @ParameterizedTest(name = "수동 완료가 먼저 잠금={0}")
    @ValueSource(booleans = {true, false})
    void 수동_완료와_늦게_온_외부_취소_성공이_동시에_와도_취소_완료는_한_번이다(boolean resolveFirst) throws Exception {
        giveUp(cancelJobId, token, 1);
        Callable<Integer> resolving = () -> resolve(cancelJobId, "외부 콘솔에서 취소 확인").andReturn().getResponse()
                .getStatus();
        Callable<Integer> succeeding = () -> {
            handler.onExternalJobSucceeded(ShopFixtures.unique(),
                    ShopFixtures.succeeded(cancelJobId, token, "CANCEL", null));
            return 0;
        };
        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch secondLocking = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        holdFirstLock(firstLocked, secondLocking, release);

        Future<Integer> resolved;
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Integer> first = executor.submit(resolveFirst ? resolving : succeeding);
            Future<Integer> second;
            try {
                assertThat(firstLocked.await(10, TimeUnit.SECONDS)).isTrue();
                second = executor.submit(resolveFirst ? succeeding : resolving);
                assertThat(secondLocking.await(10, TimeUnit.SECONDS)).isTrue();
                await().alias("먼저 잠근 쪽이 끝나기 전에는 다른 쪽도 끝나지 않는다")
                        .during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(5))
                        .until(() -> !second.isDone());
            } finally {
                release.countDown();
            }
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
            resolved = resolveFirst ? first : second;
        }

        assertThat(resolved.get()).as("수동 완료는 먼저 잠그면 200, 늦으면 409").isEqualTo(resolveFirst ? 200 : 409);
        assertThat(job(cancelJobId)).containsEntry("status", "SUCCEEDED");
        assertThat(preorderStatus()).isEqualTo(PreorderStatus.CANCELED);
        assertThat(preorders.history(preorderId)).filteredOn(entry -> entry.toStatus() == PreorderStatus.CANCELED)
                .hasSize(1);
        assertThat(canceledNotifications()).isEqualTo(1);
    }

    @Test
    void 포기하지_않은_취소_작업과_등록_작업은_끝낼_수_없다() throws Exception {
        resolve(cancelJobId, "외부 콘솔에서 취소 확인")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SYNC_JOB_NOT_RESOLVABLE"));
        UUID registerJobId = fixtures.deadLetter(accepts.accept(fixtures.customer()).preorder().id());
        resolve(registerJobId, "외부 콘솔에서 취소 확인")
                .andExpect(status().isConflict());
        resolve(UUID.randomUUID(), "외부 콘솔에서 취소 확인")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SYNC_JOB_NOT_FOUND"));
        assertThat(job(cancelJobId)).containsEntry("status", "PENDING");
    }

    @Test
    void 관리자가_아니면_403_토큰이_없으면_401() throws Exception {
        String body = "{\"reason\":\"외부 콘솔에서 취소 확인\"}";
        mockMvc.perform(post("/api/v1/admin/preorders/sync-jobs/{id}/resolve", cancelJobId)
                        .with(customer(UUID.randomUUID())).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/admin/preorders/sync-jobs/{id}/resolve", cancelJobId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 끝내는_사유가_없거나_짧으면_400() throws Exception {
        giveUp(cancelJobId, token, 1);

        asAdmin(post("/api/v1/admin/preorders/sync-jobs/{id}/resolve", cancelJobId)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        resolve(cancelJobId, "확인").andExpect(status().isBadRequest());
        assertThat(job(cancelJobId)).containsEntry("status", "DEAD_LETTER");
    }

    @Test
    void 일괄_재처리는_종류를_고르면_포기한_취소_작업을_다시_보낸다() throws Exception {
        giveUp(cancelJobId, token, 1);
        UUID registerJobId = fixtures.deadLetter(accepts.accept(fixtures.customer()).preorder().id());

        String body = "{\"syncJobIds\":[\"%s\",\"%s\"],\"ratePerSecond\":200}".formatted(cancelJobId, registerJobId);
        asAdmin(post("/api/v1/admin/preorders/sync-jobs/reprocess-batch").contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.targetCount").value(2));

        await().atMost(Duration.ofSeconds(5)).until(() -> "PENDING".equals(job(cancelJobId).get("status")));
        assertThat(cancelRequests(cancelJobId)).hasSize(2);
    }

    /** 무효화(CANCELED)는 여전히 등록 작업에만 쓴다. */
    @Test
    void 취소_작업은_DEAD_LETTER_는_되고_CANCELED_는_DB_가_막는다() {
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE preorder_sync_jobs SET status = 'CANCELED' WHERE id = ?",
                (Object) UuidBinary.toBytes(cancelJobId)))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_sync_job_canceled_register_only");
    }

    /** 결제 가능 → 사용자 취소 → 주문 없음. 외부 취소 작업이 PENDING 으로 생긴다. */
    private Booking cancelRequested() {
        AcceptResult accepted = accepts.accept(fixtures.customer());
        UUID id = accepted.preorder().id();
        String preorderToken = AcceptFixtures.tokenOf(accepted);
        handler.onExternalJobSucceeded(ShopFixtures.unique(), ShopFixtures.succeeded(
                fixtures.workerSucceeds(id, "REGISTER"), preorderToken, "REGISTER", "R-" + ShopFixtures.unique()));
        cancelStarter.start(preorders.getById(id), EventActor.USER, null, CancelReason.USER);
        handler.onOrderSettled(new PreorderOrderSettled(preorderToken, PreorderOrderSettled.Result.NO_ORDER, null,
                fixtures.cancelSequence(id), null));
        UUID jobId = jdbcTemplate.queryForObject(
                "SELECT id FROM preorder_sync_jobs WHERE preorder_id = ? AND job_type = 'CANCEL'",
                (rs, rowNum) -> UuidBinary.fromBytes(rs.getBytes(1)), (Object) UuidBinary.toBytes(id));
        return new Booking(id, preorderToken, jobId);
    }

    /** worker 가 외부 거절로 포기했다. run 은 그 결과가 속한 실행 회차다. */
    private void giveUp(UUID jobId, String preorderToken, int run) {
        handler.onExternalJobFailed(ShopFixtures.unique(), new ExternalJobFailed(jobId, preorderToken, "CANCEL",
                "REJECTED", new NewSyncAttempt(run, 1, 1, "SYSTEM", "REJECTED", 404, "NOT_FOUND", null, STARTED,
                        STARTED)));
    }

    private ResultActions resolve(UUID jobId, String reason) throws Exception {
        return asAdmin(post("/api/v1/admin/preorders/sync-jobs/{id}/resolve", jobId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"%s\"}".formatted(reason)));
    }

    private ResultActions asAdmin(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.with(admin()));
    }

    private Map<String, Object> job(UUID jobId) {
        return jdbcTemplate.queryForMap("SELECT status, run, dead_lettered_at FROM preorder_sync_jobs WHERE id = ?",
                (Object) UuidBinary.toBytes(jobId));
    }

    private PreorderStatus preorderStatus() {
        return preorders.getById(preorderId).status();
    }

    /** 예약을 처음 잠근 스레드는 잠근 채 release 까지 멈추고, 다른 스레드는 잠그러 들어갈 때 secondLocking 을 연다. */
    private void holdFirstLock(CountDownLatch firstLocked, CountDownLatch secondLocking, CountDownLatch release) {
        PreorderLedger target = AopTestUtils.getUltimateTargetObject(ledger);
        Answer<?> delegate = mockingDetails(target).getMockCreationSettings().getDefaultAnswer();
        AtomicReference<Thread> holder = new AtomicReference<>();
        willAnswer(invocation -> {
            Thread current = Thread.currentThread();
            if (holder.compareAndSet(null, current)) {
                Object locked = delegate.answer(invocation);
                firstLocked.countDown();
                awaitQuietly(release);
                return locked;
            }
            if (holder.get() != current) {
                secondLocking.countDown();
            }
            return delegate.answer(invocation);
        }).given(target).lockStatus(preorderId);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 이 작업으로 보낸 취소 메시지(보낸 순). */
    private List<Map<String, Object>> cancelRequests(UUID jobId) {
        return jdbcTemplate.queryForList("""
                SELECT JSON_UNQUOTE(JSON_EXTRACT(payload, '$.actor')) AS actor,
                       CAST(JSON_EXTRACT(payload, '$.run') AS SIGNED) AS run
                  FROM preorder_outbox_events
                 WHERE event_type = 'CANCEL_JOB_READY' AND aggregate_id = ?
                 ORDER BY id
                """, (Object) UuidBinary.toBytes(jobId));
    }

    private int canceledNotifications() {
        return fixtures.count("""
                SELECT COUNT(*) FROM preorder_outbox_events
                 WHERE event_type = 'NOTIFICATION_REQUESTED' AND aggregate_id = ?
                   AND JSON_UNQUOTE(JSON_EXTRACT(payload, '$.kind')) = 'PREORDER_CANCELED'
                """, (Object) UuidBinary.toBytes(preorderId));
    }

    record Booking(UUID preorderId, String token, UUID cancelJobId) {
    }
}
