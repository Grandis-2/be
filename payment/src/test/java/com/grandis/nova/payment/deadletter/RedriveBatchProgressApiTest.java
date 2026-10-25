package com.grandis.nova.payment.deadletter;

import com.grandis.nova.common.UuidBinary;
import com.grandis.nova.common.security.BearerTokens;
import com.grandis.nova.common.security.JwtTokenProvider;
import com.grandis.nova.common.security.RevocationChecker;
import com.grandis.nova.common.security.Role;
import com.grandis.nova.common.security.TokenType;
import com.grandis.nova.payment.support.PaymentIntegrationTest;
import com.grandis.nova.payment.support.RecordingDeadLetterRedriver;
import com.grandis.nova.payment.support.RedisContainerConfig;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 일괄 되돌리기 진행(/internal/dead-letters/redrive-batches/{batchId}). 진행은 Redis 에 두므로 이 시험만 Redis 를 띄운다.
 * 결과(SUCCEEDED · REDRIVE_FAILED)는 되돌린 메시지를 처리한 분배기가 남긴다 — 여기서는 행 상태를 직접 바꿔 흉내 낸다.
 */
@PaymentIntegrationTest
@AutoConfigureMockMvc
@Import(RedisContainerConfig.class)
class RedriveBatchProgressApiTest {

    static final String QUEUE = "payment-events";
    static final String BASE = "/internal/dead-letters";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    DeadLetters deadLetters;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    RecordingDeadLetterRedriver redriver;

    @Autowired
    JwtTokenProvider tokens;

    @MockitoBean
    RevocationChecker revocationChecker;

    String admin;
    UUID orderId;

    @BeforeEach
    void setUp() {
        admin = tokens.create("admin", Role.ADMIN, UUID.randomUUID(), TokenType.ACCESS);
        orderId = UUID.randomUUID();
    }

    @Test
    void 결과는_보낸_원래_행의_지금_상태로_세고_다시_쌓인_행은_세지_않는다() throws Exception {
        UUID first = record(event("ORDER_REFUND_REQUESTED"));
        UUID second = record(event("ORDER_REFUND_REQUESTED"));
        UUID unknown = record(event("SOMETHING_NEW"));

        String batchId = start("{\"deadLetterIds\":[\"%s\",\"%s\",\"%s\",\"%s\"],\"ratePerSecond\":200}"
                .formatted(first, second, unknown, UUID.randomUUID()), 2, 2);
        awaitFinished(batchId);
        progressOf(batchId)
                .andExpect(jsonPath("$.data.batchId").value(batchId))
                .andExpect(jsonPath("$.data.targetCount").value(2))
                .andExpect(jsonPath("$.data.skippedCount").value(2))
                .andExpect(jsonPath("$.data.sent").value(2))
                .andExpect(jsonPath("$.data.unsent").value(0))
                .andExpect(jsonPath("$.data.succeeded").value(0))
                .andExpect(jsonPath("$.data.failed").value(0))
                .andExpect(jsonPath("$.data.pending").value(2))
                .andExpect(jsonPath("$.data.startedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.lastProgressAt").isNotEmpty());

        outcome(first, "SUCCEEDED");
        outcome(second, "REDRIVE_FAILED");
        // 다시 실패한 메시지는 새 행으로 쌓인다 — 이 일괄의 대상이 아니다
        record(event("ORDER_REFUND_REQUESTED"));

        progressOf(batchId)
                .andExpect(jsonPath("$.data.sent").value(2))
                .andExpect(jsonPath("$.data.succeeded").value(1))
                .andExpect(jsonPath("$.data.failed").value(1))
                .andExpect(jsonPath("$.data.canceled").value(0))
                .andExpect(jsonPath("$.data.pending").value(0));
    }

    @Test
    void 보내지_못한_행은_unsent_로_세고_결과로_세지_않는다() throws Exception {
        UUID sent = record(event("ORDER_REFUND_REQUESTED"));
        UUID failing = record(event("ORDER_REFUND_REQUESTED"));
        redriver.failFor(failing);

        String batchId = start("{\"deadLetterIds\":[\"%s\",\"%s\"],\"ratePerSecond\":200}".formatted(sent, failing), 2, 0);
        awaitFinished(batchId);

        progressOf(batchId)
                .andExpect(jsonPath("$.data.sent").value(1))
                .andExpect(jsonPath("$.data.unsent").value(1))
                .andExpect(jsonPath("$.data.pending").value(1));
        assertThat(redriver.sentFor(failing)).isEmpty();
    }

    @Test
    void 대상이_없으면_시작과_함께_끝난다() throws Exception {
        String batchId = start("{\"deadLetterIds\":[\"%s\"],\"ratePerSecond\":200}".formatted(UUID.randomUUID()), 0, 1);

        String body = progressOf(batchId)
                .andExpect(jsonPath("$.data.pending").value(0))
                .andExpect(jsonPath("$.data.sent").value(0))
                .andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(body, "$.data.finishedAt"))
                .isEqualTo(JsonPath.read(body, "$.data.startedAt"));
    }

    @Test
    void 없는_일괄은_404_이고_관리자만_본다() throws Exception {
        asAdmin(get(BASE + "/redrive-batches/{batchId}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("REDRIVE_BATCH_NOT_FOUND"));
        String user = tokens.create("101", Role.USER, UUID.randomUUID(), TokenType.ACCESS);
        mockMvc.perform(get(BASE + "/redrive-batches/{batchId}", UUID.randomUUID())
                        .header(BearerTokens.HEADER, BearerTokens.value(user)))
                .andExpect(status().isForbidden());
    }

    private String start(String request, int targetCount, int skippedCount) throws Exception {
        String body = asAdmin(post(BASE + "/redrive-batch").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.targetCount").value(targetCount))
                .andExpect(jsonPath("$.data.skippedCount").value(skippedCount))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data.batchId");
    }

    private void awaitFinished(String batchId) {
        await().atMost(Duration.ofSeconds(5)).until(() -> JsonPath.read(progressOf(batchId).andReturn().getResponse()
                .getContentAsString(), "$.data.finishedAt") != null);
    }

    private ResultActions progressOf(String batchId) throws Exception {
        return asAdmin(get(BASE + "/redrive-batches/{batchId}", batchId)).andExpect(status().isOk());
    }

    private void outcome(UUID id, String status) {
        jdbcTemplate.update("UPDATE payment_dead_letter_events SET status = ?, outcome_at = NOW(6) WHERE id = ?",
                status, UuidBinary.toBytes(id));
    }

    private ResultActions asAdmin(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header(BearerTokens.HEADER, BearerTokens.value(admin)));
    }

    private UUID record(String body) {
        String messageId = UUID.randomUUID().toString();
        deadLetters.record(new IncomingDeadLetter(QUEUE, messageId, body, 5, null, null));
        return jdbcTemplate.queryForObject("SELECT id FROM payment_dead_letter_events WHERE message_id = ?",
                (rs, rowNum) -> UuidBinary.fromBytes(rs.getBytes(1)), messageId);
    }

    private String event(String eventType) {
        return """
                {"eventId":"%s","eventType":"%s","aggregateType":"ORDER","aggregateId":"%s",
                 "occurredAt":"2026-10-04T01:00:00Z","payload":{"amount":15000}}
                """.formatted(UUID.randomUUID(), eventType, orderId);
    }
}
