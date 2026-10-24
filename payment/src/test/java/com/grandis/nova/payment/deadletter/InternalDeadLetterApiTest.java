package com.grandis.nova.payment.deadletter;

import com.grandis.nova.common.UuidBinary;
import com.grandis.nova.common.security.BearerTokens;
import com.grandis.nova.common.security.JwtTokenProvider;
import com.grandis.nova.common.security.RevocationChecker;
import com.grandis.nova.common.security.Role;
import com.grandis.nova.common.security.TokenType;
import com.grandis.nova.common.testing.Concurrently;
import com.grandis.nova.common.testing.Concurrently.Outcome;
import com.grandis.nova.payment.support.PaymentIntegrationTest;
import com.grandis.nova.payment.support.RecordingDeadLetterRedriver;
import com.grandis.nova.payment.support.RecordingDeadLetterRedriver.Sent;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 관리자 DLQ 내부 API(/internal/dead-letters). order 가 싣는 것과 같은 진짜 관리자 토큰으로 보안 체인까지 태운다.
 * DB 를 테스트끼리 공유하므로 목록 단정은 이 테스트가 만든 주문으로만 한다. 큐로 보내기는 행 id 별로 기록하는 대역이다.
 */
@PaymentIntegrationTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class InternalDeadLetterApiTest {

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

    @Autowired
    MeterRegistry meterRegistry;

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
    void 주문_분류로_거르고_최근_것부터_지금_되돌릴_수_있는지_함께_준다() throws Exception {
        UUID processingFailed = record(event("ORDER_REFUND_REQUESTED"));
        UUID unknownType = record(event("SOMETHING_NEW"));

        asAdmin(get(BASE).param("orderId", orderId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].deadLetterId").value(contains(
                        unknownType.toString(), processingFailed.toString())))
                .andExpect(jsonPath("$.data.items[0].redrivable").value(false))
                .andExpect(jsonPath("$.data.items[1].redrivable").value(true))
                .andExpect(jsonPath("$.data.items[1].orderId").value(orderId.toString()))
                .andExpect(jsonPath("$.data.total").value(2));
        asAdmin(get(BASE).param("orderId", orderId.toString()).param("failureReason", "UNKNOWN_EVENT_TYPE"))
                .andExpect(jsonPath("$.data.items[*].deadLetterId").value(contains(unknownType.toString())));
        asAdmin(get(BASE).param("orderId", orderId.toString()).param("from", "2026-01-01T00:00:00Z")
                .param("to", "2099-01-01T00:00:00Z"))
                .andExpect(jsonPath("$.data.total").value(2));
        asAdmin(get(BASE).param("orderId", orderId.toString()).param("to", "2026-01-01T00:00:00Z"))
                .andExpect(jsonPath("$.data.total").value(0));
        asAdmin(get(BASE).param("orderId", orderId.toString()).param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.violations[0].field").value("size"));
        asAdmin(get(BASE).param("page", "-1")).andExpect(status().isBadRequest());
        asAdmin(get(BASE).param("page", String.valueOf(Integer.MAX_VALUE)).param("size", "100"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 상세는_원문과_분류를_준다() throws Exception {
        String body = event("ORDER_REFUND_REQUESTED");
        UUID id = record(body);

        asAdmin(get(BASE + "/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.body").value(body))
                .andExpect(jsonPath("$.data.sourceQueue").value(QUEUE))
                .andExpect(jsonPath("$.data.orderId").value(orderId.toString()))
                .andExpect(jsonPath("$.data.aggregateType").value("ORDER"))
                .andExpect(jsonPath("$.data.failureReason").value("PROCESSING_FAILED"))
                .andExpect(jsonPath("$.data.status").value("OPEN"));
        asAdmin(get(BASE + "/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("DEAD_LETTER_NOT_FOUND"));
    }

    @Test
    void 되돌리면_원문을_행_id_와_함께_원래_큐로_보내고_REDRIVEN_이다() throws Exception {
        String body = event("ORDER_REFUND_REQUESTED");
        UUID id = record(body);

        asAdmin(post(BASE + "/{id}/redrive", id))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("REDRIVEN"));

        assertThat(redriver.sentFor(id)).containsExactly(new Sent(QUEUE, body));
        Map<String, Object> row = row(id);
        assertThat(row.get("redriven_at")).isNotNull();
        assertThat(row.get("redrive_requested_by")).as("관리자 토큰의 주체").isEqualTo("admin");
    }

    @Test
    void 지금_코드로도_읽지_못하는_원문은_되돌리지_않는다() throws Exception {
        UUID id = record(event("SOMETHING_NEW"));

        asAdmin(post(BASE + "/{id}/redrive", id))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DEAD_LETTER_NOT_REDRIVABLE"));

        assertThat(redriver.sentFor(id)).isEmpty();
    }

    @Test
    void 보내지_못하면_503_이고_갔는지_모르니_REDRIVING_에_두었다가_1분_뒤_다시_되돌린다() throws Exception {
        UUID id = record(event("ORDER_REFUND_REQUESTED"));
        redriver.failFor(id);

        asAdmin(post(BASE + "/{id}/redrive", id))
                .andExpect(status().isServiceUnavailable());
        assertThat(row(id).get("status")).isEqualTo("REDRIVING");
        asAdmin(post(BASE + "/{id}/redrive", id))
                .andExpect(status().isConflict());
        asAdmin(get(BASE + "/{id}", id))
                .andExpect(jsonPath("$.data.redrivable").value(false));

        jdbcTemplate.update("UPDATE payment_dead_letter_events SET redrive_started_at = redrive_started_at - INTERVAL 2 MINUTE"
                + " WHERE id = ?", (Object) UuidBinary.toBytes(id));
        redriver.recover(id);

        asAdmin(get(BASE + "/{id}", id))
                .andExpect(jsonPath("$.data.redrivable").value(true));
        asAdmin(post(BASE + "/{id}/redrive", id))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("REDRIVEN"));
        assertThat(redriver.sentFor(id)).hasSize(1);
    }

    @Test
    void 여러_관리자가_동시에_되돌려도_한_번만_보낸다() throws Exception {
        UUID id = record(event("ORDER_REFUND_REQUESTED"));

        List<Outcome<Integer>> outcomes = Concurrently.run(5, i -> () -> asAdmin(post(BASE + "/{id}/redrive", id))
                .andReturn().getResponse().getStatus());

        assertThat(outcomes).allMatch(Outcome::succeeded);
        assertThat(outcomes.stream().map(Outcome::value).filter(code -> code == 202)).hasSize(1);
        assertThat(redriver.sentFor(id)).hasSize(1);
    }

    @Test
    void 버리려면_사유가_필요하고_버린_것은_되돌리거나_다시_버리지_못한다() throws Exception {
        UUID id = record(event("SOMETHING_NEW"));

        asAdmin(post(BASE + "/{id}/discard", id).contentType(MediaType.APPLICATION_JSON).content("{\"note\":\" \"}"))
                .andExpect(status().isBadRequest());
        asAdmin(post(BASE + "/{id}/discard", id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"보낸 쪽 계약 오류 — 재발행 요청\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DISCARDED"))
                .andExpect(jsonPath("$.data.discardedBy").value("admin"))
                .andExpect(jsonPath("$.data.discardNote").value("보낸 쪽 계약 오류 — 재발행 요청"));

        asAdmin(post(BASE + "/{id}/discard", id).contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"again\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DEAD_LETTER_NOT_DISCARDABLE"));
        asAdmin(post(BASE + "/{id}/redrive", id))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DEAD_LETTER_NOT_REDRIVABLE"));
    }

    // 버린 환불 요청은 다시 오지 않는다(order 에 다시 보내는 길이 없다) — 사람이 환불을 확인하도록 ERROR 로그 · 카운터로 드러낸다
    @Test
    void 환불_요청을_버리면_ERROR_로그와_카운터로_드러낸다(CapturedOutput output) throws Exception {
        UUID id = record(event("ORDER_REFUND_REQUESTED"));
        double before = discarded("ORDER_REFUND_REQUESTED");

        asAdmin(post(BASE + "/{id}/discard", id).contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"수동 환불함\"}"))
                .andExpect(status().isOk());

        assertThat(discarded("ORDER_REFUND_REQUESTED")).isEqualTo(before + 1);
        assertThat(output.getAll())
                .contains("ERROR")
                .contains("환불 요청을 버렸다 — 주문은 취소 중 그대로, 수동 환불 확인 필요 deadLetterId=" + id)
                .contains("aggregateId=" + orderId)
                .contains("discardedBy=admin")
                .contains("note=수동 환불함");
    }

    // 낼 수 있는 종류는 첫 버리기 전부터 0 으로 있다 — 첫 증가를 increase() 가 놓치지 않게
    @Test
    void 버리기_카운터는_종류마다_처음부터_있다() {
        for (String eventType : List.of("ORDER_REFUND_REQUESTED", "OTHER", "UNKNOWN")) {
            assertThat(meterRegistry.find("payment.dlq.discarded").tags("eventType", eventType).counter())
                    .as(eventType).isNotNull();
        }
    }

    @Test
    void 버리기에_실패하면_드러내지_않는다(CapturedOutput output) throws Exception {
        UUID id = record(event("SOMETHING_NEW"));
        asAdmin(post(BASE + "/{id}/discard", id).contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"first\"}"))
                .andExpect(status().isOk());
        double before = discarded("OTHER");

        asAdmin(post(BASE + "/{id}/discard", id).contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"second\"}"))
                .andExpect(status().isConflict());

        assertThat(discarded("OTHER")).isEqualTo(before);
        assertThat(output.getAll()).doesNotContain("note=second");
    }

    @Test
    void 일괄_되돌리기는_되돌릴_수_있는_OPEN_만_보내고_나머지는_건너뛴_수로_센다() throws Exception {
        UUID first = record(event("ORDER_REFUND_REQUESTED"));
        UUID second = record(event("ORDER_REFUND_REQUESTED"));
        UUID unknown = record(event("SOMETHING_NEW"));

        asAdmin(post(BASE + "/redrive-batch").contentType(MediaType.APPLICATION_JSON)
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
        UUID refund = record(event("ORDER_REFUND_REQUESTED"));
        UUID unknown = record(event("SOMETHING_NEW"));

        asAdmin(post(BASE + "/redrive-batch").contentType(MediaType.APPLICATION_JSON)
                .content("{\"eventType\":\"ORDER_REFUND_REQUESTED\",\"ratePerSecond\":200}"))
                .andExpect(status().isAccepted());
        asAdmin(post(BASE + "/redrive-batch").contentType(MediaType.APPLICATION_JSON)
                .content("{\"failureReason\":\"UNKNOWN_EVENT_TYPE\",\"ratePerSecond\":200}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.targetCount").value(0));

        await().atMost(Duration.ofSeconds(10)).until(() -> !redriver.sentFor(refund).isEmpty());
        assertThat(redriver.sentFor(unknown)).isEmpty();
    }

    @Test
    void 일괄_요청의_건수_속도_한도를_넘으면_400_이다() throws Exception {
        asAdmin(post(BASE + "/redrive-batch").contentType(MediaType.APPLICATION_JSON).content("{\"ratePerSecond\":201}"))
                .andExpect(status().isBadRequest());
        asAdmin(post(BASE + "/redrive-batch").contentType(MediaType.APPLICATION_JSON).content("{\"ratePerSecond\":0}"))
                .andExpect(status().isBadRequest());
    }

    private double discarded(String eventType) {
        return meterRegistry.get("payment.dlq.discarded").tag("eventType", eventType).counter().count();
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

    private Map<String, Object> row(UUID id) {
        return jdbcTemplate.queryForMap("SELECT * FROM payment_dead_letter_events WHERE id = ?",
                (Object) UuidBinary.toBytes(id));
    }

    private String event(String eventType) {
        return """
                {"eventId":"%s","eventType":"%s","aggregateType":"ORDER","aggregateId":"%s",
                 "occurredAt":"2026-10-04T01:00:00Z","payload":{"amount":15000}}
                """.formatted(UUID.randomUUID(), eventType, orderId);
    }
}
