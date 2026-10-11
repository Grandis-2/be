package com.grandis.nova.preorder.deadletter;

import com.grandis.nova.common.UuidBinary;
import com.grandis.nova.common.testing.Concurrently.Outcome;
import com.grandis.nova.common.testing.Concurrently;
import com.grandis.nova.preorder.accept.application.AcceptResult;
import com.grandis.nova.preorder.accept.application.PreorderAcceptService;
import com.grandis.nova.preorder.integration.catalog.CatalogClient;
import com.grandis.nova.preorder.support.AcceptFixtures;
import com.grandis.nova.preorder.support.PreorderIntegrationTest;
import com.grandis.nova.preorder.support.RecordingDeadLetterRedriver.Sent;
import com.grandis.nova.preorder.support.RecordingDeadLetterRedriver;
import com.grandis.nova.preorder.support.ShopFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.grandis.nova.preorder.support.AccessTokens.admin;
import static com.grandis.nova.preorder.support.AccessTokens.customer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** DB 를 테스트끼리 공유하므로 목록 단정은 이 테스트가 만든 예약으로만 한다. 큐로 보내기는 행 id 별로 기록하는 대역이다. */
@PreorderIntegrationTest
@AutoConfigureMockMvc
class AdminDeadLetterApiTest {

    static final String QUEUE = "preorder-events";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    DeadLetters deadLetters;

    @Autowired
    PreorderAcceptService acceptService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @MockitoBean
    CatalogClient catalogClient;

    @Autowired
    RecordingDeadLetterRedriver redriver;

    ShopFixtures fixtures;
    UUID customerId;
    String token;

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
        customerId = fixtures.customer();
        AcceptResult accepted = new AcceptFixtures(acceptService, fixtures, catalogClient).accept(customerId);
        token = AcceptFixtures.tokenOf(accepted);
    }

    @Test
    void 예약_회원_분류로_거르고_최근_것부터_지금_되돌릴_수_있는지_함께_준다() throws Exception {
        UUID processingFailed = record(event("EXTERNAL_JOB_SUCCEEDED"));
        UUID unknownType = record(event("SOMETHING_NEW"));

        mockMvc.perform(get("/api/v1/admin/preorders/dead-letters").param("preorderId", token).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].deadLetterId").value(contains(
                        unknownType.toString(), processingFailed.toString())))
                .andExpect(jsonPath("$.data.items[0].redrivable").value(false))
                .andExpect(jsonPath("$.data.items[1].redrivable").value(true))
                .andExpect(jsonPath("$.data.items[1].preorderId").value(token))
                .andExpect(jsonPath("$.data.total").value(2));
        mockMvc.perform(get("/api/v1/admin/preorders/dead-letters").param("customerId", customerId.toString())
                        .param("failureReason", "UNKNOWN_EVENT_TYPE").with(admin()))
                .andExpect(jsonPath("$.data.items[*].deadLetterId").value(contains(unknownType.toString())));
    }

    @Test
    void 상세는_원문과_분류를_준다() throws Exception {
        String body = event("EXTERNAL_JOB_SUCCEEDED");
        UUID id = record(body);

        mockMvc.perform(get("/api/v1/admin/preorders/dead-letters/{id}", id).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.body").value(body))
                .andExpect(jsonPath("$.data.sourceQueue").value(QUEUE))
                .andExpect(jsonPath("$.data.failureReason").value("PROCESSING_FAILED"))
                .andExpect(jsonPath("$.data.status").value("OPEN"));
        mockMvc.perform(get("/api/v1/admin/preorders/dead-letters/{id}", UUID.randomUUID()).with(admin()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("DEAD_LETTER_NOT_FOUND"));
    }

    @Test
    void 되돌리면_원문을_행_id_와_함께_원래_큐로_보내고_REDRIVEN_이다() throws Exception {
        String body = event("EXTERNAL_JOB_SUCCEEDED");
        UUID id = record(body);

        mockMvc.perform(post("/api/v1/admin/preorders/dead-letters/{id}/redrive", id).with(admin()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("REDRIVEN"));

        assertThat(redriver.sentFor(id)).containsExactly(new Sent(QUEUE, body));
        Map<String, Object> row = row(id);
        assertThat(row.get("redriven_at")).isNotNull();
        assertThat(row.get("redrive_requested_by")).isNotNull();
    }

    @Test
    void 지금_코드로도_읽지_못하는_원문은_되돌리지_않는다() throws Exception {
        UUID id = record(event("SOMETHING_NEW"));

        mockMvc.perform(post("/api/v1/admin/preorders/dead-letters/{id}/redrive", id).with(admin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DEAD_LETTER_NOT_REDRIVABLE"));

        assertThat(redriver.sentFor(id)).isEmpty();
    }

    @Test
    void 보내지_못하면_503_이고_갔는지_모르니_REDRIVING_에_두었다가_1분_뒤_다시_되돌린다() throws Exception {
        UUID id = record(event("EXTERNAL_JOB_SUCCEEDED"));
        redriver.failFor(id);

        mockMvc.perform(post("/api/v1/admin/preorders/dead-letters/{id}/redrive", id).with(admin()))
                .andExpect(status().isServiceUnavailable());
        assertThat(row(id).get("status")).isEqualTo("REDRIVING");
        mockMvc.perform(post("/api/v1/admin/preorders/dead-letters/{id}/redrive", id).with(admin()))
                .andExpect(status().isConflict());
        mockMvc.perform(get("/api/v1/admin/preorders/dead-letters/{id}", id).with(admin()))
                .andExpect(jsonPath("$.data.redrivable").value(false));

        jdbcTemplate.update("UPDATE preorder_dead_letter_events SET redrive_started_at = redrive_started_at - INTERVAL 2 MINUTE"
                + " WHERE id = ?", (Object) UuidBinary.toBytes(id));
        redriver.recover(id);

        mockMvc.perform(get("/api/v1/admin/preorders/dead-letters/{id}", id).with(admin()))
                .andExpect(jsonPath("$.data.redrivable").value(true));
        mockMvc.perform(post("/api/v1/admin/preorders/dead-letters/{id}/redrive", id).with(admin()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("REDRIVEN"));
        assertThat(redriver.sentFor(id)).hasSize(1);
    }

    @Test
    void 여러_관리자가_동시에_되돌려도_한_번만_보낸다() throws Exception {
        UUID id = record(event("EXTERNAL_JOB_SUCCEEDED"));

        List<Outcome<Integer>> outcomes = Concurrently.run(5, i -> () -> mockMvc
                .perform(post("/api/v1/admin/preorders/dead-letters/{id}/redrive", id).with(admin()))
                .andReturn().getResponse().getStatus());

        assertThat(outcomes).allMatch(Outcome::succeeded);
        assertThat(outcomes.stream().map(Outcome::value).filter(code -> code == 202)).hasSize(1);
        assertThat(redriver.sentFor(id)).hasSize(1);
    }

    @Test
    void 버리려면_사유가_필요하고_버린_것은_되돌리거나_다시_버리지_못한다() throws Exception {
        UUID id = record(event("SOMETHING_NEW"));

        mockMvc.perform(post("/api/v1/admin/preorders/dead-letters/{id}/discard", id).with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\" \"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/admin/preorders/dead-letters/{id}/discard", id).with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"보낸 쪽 계약 오류 — 재발행 요청\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DISCARDED"))
                .andExpect(jsonPath("$.data.discardNote").value("보낸 쪽 계약 오류 — 재발행 요청"));

        mockMvc.perform(post("/api/v1/admin/preorders/dead-letters/{id}/discard", id).with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"again\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DEAD_LETTER_NOT_DISCARDABLE"));
    }

    @Test
    void 일괄_되돌리기는_되돌릴_수_있는_OPEN_만_보내고_나머지는_건너뛴_수로_센다() throws Exception {
        UUID first = record(event("EXTERNAL_JOB_SUCCEEDED"));
        UUID second = record(event("PREORDER_ORDER_SETTLED"));
        UUID unknown = record(event("SOMETHING_NEW"));

        mockMvc.perform(post("/api/v1/admin/preorders/dead-letters/redrive-batch").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deadLetterIds\":[\"%s\",\"%s\",\"%s\",\"%s\"],\"ratePerSecond\":50}"
                                .formatted(first, second, unknown, UUID.randomUUID())))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.targetCount").value(2))
                .andExpect(jsonPath("$.data.skippedCount").value(2));

        await().atMost(Duration.ofSeconds(5))
                .until(() -> !redriver.sentFor(first).isEmpty() && !redriver.sentFor(second).isEmpty());
        assertThat(redriver.sentFor(unknown)).isEmpty();
    }

    @Test
    void id_없이_조건만_주면_그_조건의_OPEN_을_되돌린다() throws Exception {
        UUID expiry = record(event("PREORDER_EXPIRY_REQUESTED"));
        UUID settled = record(event("PREORDER_ORDER_SETTLED"));

        mockMvc.perform(post("/api/v1/admin/preorders/dead-letters/redrive-batch").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventType\":\"PREORDER_EXPIRY_REQUESTED\",\"ratePerSecond\":200}"))
                .andExpect(status().isAccepted());
        mockMvc.perform(post("/api/v1/admin/preorders/dead-letters/redrive-batch").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"failureReason\":\"UNKNOWN_EVENT_TYPE\",\"ratePerSecond\":200}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.targetCount").value(0));

        await().atMost(Duration.ofSeconds(10)).until(() -> !redriver.sentFor(expiry).isEmpty());
        assertThat(redriver.sentFor(settled)).isEmpty();
    }

    @Test
    void 조건을_모두_비우면_처리_실패_행만_되돌려_되돌릴_수_없는_행이_앞을_막지_않는다() throws Exception {
        UUID unknown = record(event("SOMETHING_NEW"));
        UUID failed = record(event("PREORDER_CAMPAIGN_CANCELED"));

        mockMvc.perform(post("/api/v1/admin/preorders/dead-letters/redrive-batch").with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"ratePerSecond\":200}"))
                .andExpect(status().isAccepted());

        await().atMost(Duration.ofSeconds(20)).until(() -> !redriver.sentFor(failed).isEmpty());
        assertThat(redriver.sentFor(unknown)).isEmpty();
    }

    @Test
    void 회원_토큰으로는_볼_수_없다() throws Exception {
        mockMvc.perform(get("/api/v1/admin/preorders/dead-letters").with(customer(customerId)))
                .andExpect(status().isForbidden());
    }

    private UUID record(String body) {
        String messageId = ShopFixtures.unique();
        deadLetters.record(new IncomingDeadLetter(QUEUE, messageId, body, 5, null, null));
        return jdbcTemplate.queryForObject("SELECT id FROM preorder_dead_letter_events WHERE message_id = ?",
                (rs, rowNum) -> UuidBinary.fromBytes(rs.getBytes(1)), messageId);
    }

    private Map<String, Object> row(UUID id) {
        return jdbcTemplate.queryForMap("SELECT * FROM preorder_dead_letter_events WHERE id = ?",
                (Object) UuidBinary.toBytes(id));
    }

    private String event(String eventType) {
        return """
                {"eventId":"%s","eventType":"%s","aggregateType":"PREORDER",
                 "aggregateId":"00000000-0000-7000-8000-000000000001",
                 "occurredAt":"2026-09-03T01:00:03.470Z","payload":{"preorderId":"%s"}}
                """.formatted(ShopFixtures.unique(), eventType, token);
    }
}
