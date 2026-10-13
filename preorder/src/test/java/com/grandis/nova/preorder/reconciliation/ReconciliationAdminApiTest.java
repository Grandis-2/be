package com.grandis.nova.preorder.reconciliation;

import static com.grandis.nova.preorder.support.AccessTokens.admin;
import static com.grandis.nova.preorder.support.AccessTokens.customer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.grandis.nova.common.UuidBinary;
import com.grandis.nova.common.testing.Concurrently;
import com.grandis.nova.common.testing.Concurrently.Outcome;
import com.grandis.nova.common.web.ApiResponse;
import com.grandis.nova.preorder.accept.application.AcceptResult;
import com.grandis.nova.preorder.accept.application.PreorderAcceptService;
import com.grandis.nova.preorder.integration.catalog.CatalogClient;
import com.grandis.nova.preorder.integration.order.Cancelability;
import com.grandis.nova.preorder.integration.order.OrderClient;
import com.grandis.nova.preorder.support.AcceptFixtures;
import com.grandis.nova.preorder.support.PreorderIntegrationTest;
import com.grandis.nova.preorder.support.ShopFixtures;
import com.grandis.nova.preorder.support.ShopFixtures.PreorderProduct;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * 결과 표는 이 시험만 쓰므로 시험마다 비운다. 대기 · 실행 중 표식이 DB 전체에서 하나라 다른 시험과 겹치면 안 된다.
 * 다른 시험이 남긴 막 오픈한 회차는 몰리는 구간 밖으로 민다 — 실행 요청 시험이 그 회차에 막히지 않게.
 */
@PreorderIntegrationTest
@AutoConfigureMockMvc
class ReconciliationAdminApiTest {

    private static final String RUNS = "/api/v1/admin/preorders/reconciliation/runs";
    private static final String FINDINGS = "/api/v1/admin/preorders/reconciliation/findings";
    private static final Instant TWO_HOURS_AGO = Instant.now().minus(Duration.ofHours(2));

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    PreorderAcceptService acceptService;

    @MockitoBean
    CatalogClient catalogClient;

    @MockitoBean
    OrderClient orderClient;

    ShopFixtures fixtures;
    AcceptFixtures accepts;

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
        accepts = new AcceptFixtures(acceptService, fixtures, catalogClient);
        jdbcTemplate.update("DELETE FROM reconciliation_findings");
        jdbcTemplate.update("DELETE FROM reconciliation_runs");
        jdbcTemplate.update("""
                UPDATE preorder_campaigns SET opens_at = opens_at - INTERVAL 2 HOUR
                 WHERE opens_at > UTC_TIMESTAMP(6) - INTERVAL 1 HOUR AND opens_at <= UTC_TIMESTAMP(6)
                """);
    }

    @Test
    void 실행_요청은_REQUESTED_한_줄을_남기고_202_다() throws Exception {
        PreorderProduct product = fixtures.openPreorderProduct();

        asAdmin(post(RUNS).contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":\"%s\"}".formatted(product.productId())))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("REQUESTED"))
                .andExpect(jsonPath("$.data.verdict").value(nullValue()))
                .andExpect(jsonPath("$.data.requestedBy").value("admin"))
                .andExpect(jsonPath("$.data.productId").value(product.productId().toString()))
                .andExpect(jsonPath("$.data.startedAt").value(nullValue()))
                .andExpect(jsonPath("$.data.findingCount").value(0));

        assertThat(jdbcTemplate.queryForMap("SELECT status, requested_by, BIN_TO_UUID(product_id) AS product_id"
                        + " FROM reconciliation_runs"))
                .containsEntry("status", "REQUESTED").containsEntry("requested_by", "admin")
                .containsEntry("product_id", product.productId().toString());
    }

    @Test
    void 본문이_없으면_전체_검사를_요청한다() throws Exception {
        asAdmin(post(RUNS))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.productId").value(nullValue()));
    }

    @Test
    void 대기_실행_중인_검사가_있으면_409_다() throws Exception {
        run("RUNNING", null, TWO_HOURS_AGO);

        asAdmin(post(RUNS))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RECONCILIATION_RUN_ACTIVE"));
        assertThat(count("SELECT COUNT(*) FROM reconciliation_runs")).isEqualTo(1);
    }

    @Test
    void 오픈한_지_1시간이_안_된_회차가_있으면_409_다() throws Exception {
        Instant now = Instant.now();
        fixtures.preorderProduct(now.minus(Duration.ofMinutes(10)), now.plus(Duration.ofHours(1)));

        asAdmin(post(RUNS))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RECONCILIATION_INTAKE_RUSH"));
        assertThat(count("SELECT COUNT(*) FROM reconciliation_runs")).isZero();
    }

    @Test
    void 회차가_없는_상품이면_404_사용자_토큰이면_403_이다() throws Exception {
        asAdmin(post(RUNS).contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":\"%s\"}".formatted(UUID.randomUUID())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PRODUCT_NOT_FOUND"));
        mockMvc.perform(post(RUNS).with(customer(fixtures.customer())))
                .andExpect(status().isForbidden());
    }

    @Test
    void 실행_목록은_최근부터_코드별_건수와_판정을_준다() throws Exception {
        UUID failed = run("FAILED", null, TWO_HOURS_AGO.minusSeconds(60));
        UUID mismatched = run("COMPLETED", null, TWO_HOURS_AGO);
        finding(mismatched, "REGISTERED_NOT_IN_MOCK", "k-1");
        finding(mismatched, "REGISTERED_NOT_IN_MOCK", "k-2");
        finding(mismatched, "STUCK", "k-3");
        UUID matched = run("COMPLETED", null, TWO_HOURS_AGO.plusSeconds(60));
        UUID running = run("RUNNING", null, TWO_HOURS_AGO.plusSeconds(120));

        asAdmin(get(RUNS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(4))
                .andExpect(jsonPath("$.data.items[*].runId").value(contains(running.toString(), matched.toString(),
                        mismatched.toString(), failed.toString())))
                .andExpect(jsonPath("$.data.items[*].verdict").value(contains(null, "MATCHED", "MISMATCHED",
                        "UNDETERMINED")))
                .andExpect(jsonPath("$.data.items[2].findingCount").value(3))
                .andExpect(jsonPath("$.data.items[2].findingCounts.REGISTERED_NOT_IN_MOCK").value(2))
                .andExpect(jsonPath("$.data.items[2].findingCounts.STUCK").value(1))
                .andExpect(jsonPath("$.data.items[2].heldCount").value(4))
                .andExpect(jsonPath("$.data.items[3].errorMessage").value("Mock 목록 조회 실패(3페이지, 시간 초과)"))
                .andExpect(jsonPath("$.data.items[3].heldCount").value(nullValue()));
        asAdmin(get(RUNS).param("page", "1").param("size", "3"))
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.total").value(4));
    }

    @Test
    void 불일치_목록은_코드_대상_순으로_검사_때_본_값을_객체로_준다() throws Exception {
        UUID runId = run("COMPLETED", null, TWO_HOURS_AGO);
        finding(runId, "STUCK", "k-2");
        finding(runId, "REGISTERED_NOT_IN_MOCK", "k-9");
        finding(runId, "REGISTERED_NOT_IN_MOCK", "k-1");

        asAdmin(get(RUNS + "/{runId}/findings", runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].targetKey").value(contains("k-1", "k-9", "k-2")))
                .andExpect(jsonPath("$.data.items[0].beSnapshot.status").value("REGISTERED"))
                .andExpect(jsonPath("$.data.items[0].mockSnapshot").value(nullValue()))
                .andExpect(jsonPath("$.data.items[0].actionStatus").value("NOT_STARTED"))
                .andExpect(jsonPath("$.data.items[0].actionType").value(nullValue()));
        asAdmin(get(RUNS + "/{runId}/findings", runId).param("checkCode", "STUCK"))
                .andExpect(jsonPath("$.data.items[*].targetKey").value(contains("k-2")))
                .andExpect(jsonPath("$.data.total").value(1));
        asAdmin(get(RUNS + "/{runId}/findings", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RECONCILIATION_RUN_NOT_FOUND"));
    }

    @Test
    void 두_실행을_신규_잔여_해소로_나눈다() throws Exception {
        UUID before = run("COMPLETED", null, TWO_HOURS_AGO);
        finding(before, "REGISTERED_NOT_IN_MOCK", "k-1");
        finding(before, "REGISTERED_NOT_IN_MOCK", "k-2");
        finding(before, "STUCK", "k-3");
        UUID after = run("COMPLETED", null, TWO_HOURS_AGO.plusSeconds(60));
        finding(after, "REGISTERED_NOT_IN_MOCK", "k-2");
        finding(after, "STUCK", "k-3");
        finding(after, "MOCK_ONLY", "k-4");

        asAdmin(get(RUNS + "/{runId}/diff", after).param("against", before.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.introduced[*].targetKey").value(contains("k-4")))
                .andExpect(jsonPath("$.data.introduced[0].checkCode").value("MOCK_ONLY"))
                .andExpect(jsonPath("$.data.persisting[*].targetKey").value(contains("k-2", "k-3")))
                .andExpect(jsonPath("$.data.resolved[*].targetKey").value(contains("k-1")));
    }

    @Test
    void 범위가_다르거나_끝나지_않은_실행은_비교하지_않는다() throws Exception {
        UUID all = run("COMPLETED", null, TWO_HOURS_AGO);
        UUID oneProduct = run("COMPLETED", fixtures.openPreorderProduct().productId(), TWO_HOURS_AGO.plusSeconds(60));
        UUID failed = run("FAILED", null, TWO_HOURS_AGO.plusSeconds(120));

        asAdmin(get(RUNS + "/{runId}/diff", oneProduct).param("against", all.toString()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RECONCILIATION_NOT_COMPARABLE"));
        asAdmin(get(RUNS + "/{runId}/diff", failed).param("against", all.toString()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RECONCILIATION_NOT_COMPARABLE"));
        asAdmin(get(RUNS + "/{runId}/diff", all).param("against", UUID.randomUUID().toString()))
                .andExpect(status().isNotFound());
    }

    @Test
    void 재처리_조치는_등록_작업을_되돌리고_그_건을_REQUESTED_로_남긴다() throws Exception {
        AcceptResult accepted = accepts.accept(fixtures.customer());
        UUID jobId = fixtures.deadLetter(accepted.preorder().id());
        UUID findingId = finding(run("COMPLETED", null, TWO_HOURS_AGO), "PENDING_BUT_MOCK_ACTIVE",
                AcceptFixtures.tokenOf(accepted));

        asAdmin(post(FINDINGS + "/{id}/reprocess", findingId))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.actionStatus").value("REQUESTED"))
                .andExpect(jsonPath("$.data.actionType").value("REPROCESS"))
                .andExpect(jsonPath("$.data.actionRequestedBy").value("admin"))
                .andExpect(jsonPath("$.data.actionRequestedAt").exists());

        assertThat(jobStatus(jobId)).isEqualTo("PENDING");
    }

    @Test
    void 재처리가_거절되면_조치도_남지_않는다() throws Exception {
        AcceptResult accepted = accepts.accept(fixtures.customer());
        UUID findingId = finding(run("COMPLETED", null, TWO_HOURS_AGO), "PENDING_BUT_MOCK_ACTIVE",
                AcceptFixtures.tokenOf(accepted));

        asAdmin(post(FINDINGS + "/{id}/reprocess", findingId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SYNC_JOB_NOT_REPROCESSABLE"));

        assertThat(actionStatus(findingId)).isEqualTo("NOT_STARTED");
    }

    @Test
    void 코드에_맞지_않는_조치나_이미_조치한_건은_409_다() throws Exception {
        AcceptResult accepted = accepts.accept(fixtures.customer());
        UUID jobId = fixtures.deadLetter(accepted.preorder().id());
        UUID runId = run("COMPLETED", null, TWO_HOURS_AGO);
        UUID wrongCode = finding(runId, "REGISTERED_NOT_IN_MOCK", AcceptFixtures.tokenOf(accepted));
        UUID taken = finding(runId, "PENDING_BUT_MOCK_ACTIVE", AcceptFixtures.tokenOf(accepted));
        jdbcTemplate.update("""
                UPDATE reconciliation_findings
                   SET action_status = 'REQUESTED', action_type = 'REPROCESS', action_requested_by = 'other',
                       action_requested_at = UTC_TIMESTAMP(6)
                 WHERE id = ?
                """, (Object) UuidBinary.toBytes(taken));

        asAdmin(post(FINDINGS + "/{id}/reprocess", wrongCode))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RECONCILIATION_ACTION_NOT_ALLOWED"));
        asAdmin(post(FINDINGS + "/{id}/reprocess", taken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RECONCILIATION_ACTION_TAKEN"));
        asAdmin(post(FINDINGS + "/{id}/reprocess", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RECONCILIATION_FINDING_NOT_FOUND"));

        assertThat(jobStatus(jobId)).isEqualTo("DEAD_LETTER");
        assertThat(actionStatus(wrongCode)).isEqualTo("NOT_STARTED");
    }

    @Test
    void 같은_건을_동시에_재처리해도_하나만_받아들인다() throws Exception {
        AcceptResult accepted = accepts.accept(fixtures.customer());
        UUID jobId = fixtures.deadLetter(accepted.preorder().id());
        UUID findingId = finding(run("COMPLETED", null, TWO_HOURS_AGO), "PENDING_BUT_MOCK_ACTIVE",
                AcceptFixtures.tokenOf(accepted));

        List<Outcome<Integer>> outcomes = Concurrently.run(2, i -> () ->
                asAdmin(post(FINDINGS + "/{id}/reprocess", findingId)).andReturn().getResponse().getStatus());

        assertThat(outcomes).extracting(Outcome::value).containsExactlyInAnyOrder(202, 409);
        assertThat(count("""
                SELECT COUNT(*) FROM preorder_outbox_events
                 WHERE aggregate_id = ? AND event_type = 'REGISTER_JOB_READY' AND JSON_EXTRACT(payload, '$.run') = 2
                """, UuidBinary.toBytes(jobId))).isEqualTo(1);
    }

    @Test
    void 관리자_예약_취소_조치는_예약을_취소_중으로_바꾸고_그_건을_REQUESTED_로_남긴다() throws Exception {
        AcceptResult accepted = accepts.accept(fixtures.customer());
        given(orderClient.getCancelability(accepted.preorder().id()))
                .willReturn(ApiResponse.ok(new Cancelability(null, true, null)));
        UUID findingId = finding(run("COMPLETED", null, TWO_HOURS_AGO), "PENDING_BUT_MOCK_CANCEL_MARKER",
                AcceptFixtures.tokenOf(accepted));

        asAdmin(post(FINDINGS + "/{id}/cancel", findingId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"외부에서 취소됨\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.actionStatus").value("REQUESTED"))
                .andExpect(jsonPath("$.data.actionType").value("ADMIN_CANCEL"));

        assertThat(jdbcTemplate.queryForMap("""
                SELECT p.status, e.actor, e.reason
                  FROM preorders p JOIN preorder_events e ON e.preorder_id = p.id AND e.event_sequence = p.event_sequence
                 WHERE p.id = ?
                """, (Object) UuidBinary.toBytes(accepted.preorder().id())))
                .containsEntry("status", "CANCELING").containsEntry("actor", "ADMIN")
                .containsEntry("reason", "외부에서 취소됨");
    }

    /** 취소는 잠그지 않고 먼저 하므로 둘 다 취소까지 갈 수 있다. 조치를 남기는 조건부 갱신이 뒤의 것을 409 로 돌린다. */
    @Test
    void 같은_건을_동시에_관리자_취소해도_하나만_받아들이고_취소_이력은_한_번이다() throws Exception {
        AcceptResult accepted = accepts.accept(fixtures.customer());
        given(orderClient.getCancelability(accepted.preorder().id()))
                .willReturn(ApiResponse.ok(new Cancelability(null, true, null)));
        UUID findingId = finding(run("COMPLETED", null, TWO_HOURS_AGO), "PENDING_BUT_MOCK_CANCEL_MARKER",
                AcceptFixtures.tokenOf(accepted));

        List<Outcome<Integer>> outcomes = Concurrently.run(2, i -> () ->
                asAdmin(post(FINDINGS + "/{id}/cancel", findingId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"외부에서 취소됨\"}")).andReturn().getResponse().getStatus());

        assertThat(outcomes).extracting(Outcome::value).containsExactlyInAnyOrder(202, 409);
        assertThat(count("SELECT COUNT(*) FROM preorder_events WHERE preorder_id = ? AND to_status = 'CANCELING'",
                UuidBinary.toBytes(accepted.preorder().id()))).isEqualTo(1);
    }

    @Test
    void 관리자_예약_취소_조치는_사유가_필요하고_코드에_맞아야_한다() throws Exception {
        AcceptResult accepted = accepts.accept(fixtures.customer());
        UUID runId = run("COMPLETED", null, TWO_HOURS_AGO);
        UUID marker = finding(runId, "PENDING_BUT_MOCK_CANCEL_MARKER", AcceptFixtures.tokenOf(accepted));
        UUID stuck = finding(runId, "STUCK", AcceptFixtures.tokenOf(accepted));

        asAdmin(post(FINDINGS + "/{id}/cancel", marker).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.violations[0].field").value("reason"));
        asAdmin(post(FINDINGS + "/{id}/cancel", stuck).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"외부에서 취소됨\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RECONCILIATION_ACTION_NOT_ALLOWED"));

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM preorders WHERE id = ?", String.class,
                (Object) UuidBinary.toBytes(accepted.preorder().id()))).isEqualTo("PENDING_SYNC");
    }

    /** 상태에 맞는 시각 · 건수를 채운 실행 한 줄 */
    private UUID run(String status, UUID productId, Instant createdAt) {
        UUID id = UUID.randomUUID();
        boolean started = !status.equals("REQUESTED");
        boolean finished = status.equals("COMPLETED") || status.equals("FAILED");
        boolean completed = status.equals("COMPLETED");
        jdbcTemplate.update("""
                        INSERT INTO reconciliation_runs (id, requested_by, product_id, status, started_at, cutoff_at,
                                                         finished_at, error_message, held_count, pending_sync_count,
                                                         canceling_count, dead_letter_count, created_at, updated_at)
                        VALUES (?, NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                UuidBinary.toBytes(id), productId == null ? null : UuidBinary.toBytes(productId), status,
                started ? utc(createdAt) : null, started ? utc(createdAt.minus(Duration.ofMinutes(45))) : null,
                finished ? utc(createdAt.plusSeconds(30)) : null,
                status.equals("FAILED") ? "Mock 목록 조회 실패(3페이지, 시간 초과)" : null,
                completed ? 4 : null, completed ? 3 : null, completed ? 2 : null, completed ? 1 : null,
                utc(createdAt), utc(createdAt));
        return id;
    }

    private UUID finding(UUID runId, String checkCode, String targetKey) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                        INSERT INTO reconciliation_findings (id, run_id, check_code, target_key, be_snapshot,
                                                             first_detected_at, action_status, created_at, updated_at)
                        VALUES (?, ?, ?, ?, JSON_OBJECT('token', ?, 'status', 'REGISTERED'), UTC_TIMESTAMP(6),
                                'NOT_STARTED', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                        """,
                UuidBinary.toBytes(id), UuidBinary.toBytes(runId), checkCode, targetKey, targetKey);
        return id;
    }

    private String jobStatus(UUID jobId) {
        return jdbcTemplate.queryForObject("SELECT status FROM preorder_sync_jobs WHERE id = ?", String.class,
                (Object) UuidBinary.toBytes(jobId));
    }

    private String actionStatus(UUID findingId) {
        return jdbcTemplate.queryForObject("SELECT action_status FROM reconciliation_findings WHERE id = ?",
                String.class, (Object) UuidBinary.toBytes(findingId));
    }

    private int count(String sql, Object... args) {
        return fixtures.count(sql, args);
    }

    private ResultActions asAdmin(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.with(admin()));
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
