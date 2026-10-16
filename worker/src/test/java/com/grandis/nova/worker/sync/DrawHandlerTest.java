package com.grandis.nova.worker.sync;

import com.grandis.nova.common.sqs.BatchQueueConsumer;
import com.grandis.nova.common.sqs.MessageOutcome;
import com.grandis.nova.common.sqs.testing.SqsTestConfig;
import com.grandis.nova.common.sqs.testing.TestQueues;
import com.grandis.nova.worker.mock.FakeMock;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import software.amazon.awssdk.services.sqs.model.Message;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.List;

import static com.grandis.nova.worker.mock.FakeMock.html;
import static com.grandis.nova.worker.mock.FakeMock.json;
import static com.grandis.nova.worker.sync.Draws.drawn;
import static com.grandis.nova.worker.sync.Draws.error;
import static com.grandis.nova.worker.sync.Draws.routes;
import static com.grandis.nova.worker.sync.Draws.status;
import static com.grandis.nova.worker.sync.Draws.winners;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.reset;

/**
 * 드로우 메시지 하나의 처리. 가짜 mock 에 실제 HTTP 로 부르고, 추첨 결과 · DLQ · 돌려보낸 메시지는 실제 SQS(Floci)에서 꺼내 본다.
 * 큐 소비기는 대역으로 바꾸고 처리기를 직접 불러, 메시지를 어떻게 끝내는지(지움 · 늦춰 다시 받음 · DLQ)를 기다리지 않고 본다.
 */
@SpringBootTest(properties = {
        "nova.sqs.region=ap-northeast-2",
        "nova.worker.mock.connect-timeout=300ms",
        "nova.worker.mock.read-timeout=300ms",
        "nova.worker.mock.slow-call-threshold=150ms",
        "nova.worker.retry.in-round-delay-min=10ms",
        "nova.worker.retry.in-round-delay-max=20ms",
        "nova.worker.draw.retry-delays=10s,30s,1m",
        "nova.worker.draw.wait-delays=1m,5m",
        // 이 시험의 실패가 차단기 · 연쇄 거절 차단을 건드리지 않게 문턱을 높인다
        "nova.worker.circuit.window=100",
        "nova.worker.circuit.minimum-calls=100",
        "nova.worker.reject-guard.invalid-requests=100"})
@Import(SqsTestConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DrawHandlerTest {

    static final FakeMock MOCK = new FakeMock();
    static final String ENTERED = "{\"entryId\":\"e\",\"drawId\":\"d\",\"customerId\":\"c\",\"receivedAt\":\"2026-10-14T00:00:00Z\"}";
    static final Duration NONE = Duration.ofSeconds(2);

    @Autowired
    DrawHandler handler;

    @Autowired
    TestQueues queues;

    @Autowired
    MeterRegistry meters;

    /** 소비기가 큐를 받지 않게 대역으로 바꾼다. 처리기는 시험이 직접 부른다. */
    @MockitoBean
    BatchQueueConsumer registerConsumer;

    @MockitoBean
    BatchQueueConsumer cancelConsumer;

    @MockitoBean
    BatchQueueConsumer drawConsumer;

    @MockitoSpyBean
    ResultPublisher publisher;

    final Draws draw = new Draws();

    @DynamicPropertySource
    static void mock(DynamicPropertyRegistry registry) {
        registry.add("nova.worker.mock.base-url", () -> MOCK.baseUrl().toString());
    }

    @AfterAll
    static void closeMock() {
        MOCK.close();
    }

    @Test
    void 응모를_응모_id_멱등_키로_넘기고_성공하면_지운다() {
        MOCK.respond(json(201, ENTERED));

        assertThat(handler.handle(draw.entry())).isEqualTo(MessageOutcome.delete());

        assertThat(draw.calls(MOCK)).containsExactly("POST /external/draw-entries");
        FakeMock.Received sent = MOCK.requests().stream()
                .filter(request -> draw.entryId.toString().equals(request.idempotencyKey())).findFirst().orElseThrow();
        assertThat(sent.body()).as("응모 id · 회차 id · 회원 id").isEqualTo("{\"entryId\":\"%s\",\"drawId\":\"%s\",\"customerId\":\"%s\"}"
                        .formatted(draw.entryId, draw.drawId, draw.customerId));
        assertThat(draw.result(queues, NONE)).as("응모는 order 에 알리지 않는다").isEmpty();
    }

    /** 결과를 모르는 실패도 조회하지 않는다 — 같은 키로 다시 보내면 Mock 이 재생한다. 받은 횟수가 늘수록 늦게 다시 받는다. */
    @Test
    void 응모가_일시_실패하면_지우지_않고_받은_횟수에_따라_늦춰_다시_받는다() {
        MOCK.respond(html(503));

        assertThat(handler.handle(draw.message(draw.entryBody(), 1)))
                .isEqualTo(MessageOutcome.returnAfter(Duration.ofSeconds(10)));
        assertThat(handler.handle(draw.message(draw.entryBody(), 2)))
                .isEqualTo(MessageOutcome.returnAfter(Duration.ofSeconds(30)));
        assertThat(handler.handle(draw.message(draw.entryBody(), 9))).as("넘으면 마지막 값")
                .isEqualTo(MessageOutcome.returnAfter(Duration.ofMinutes(1)));
        assertThat(draw.calls(MOCK)).as("차례마다 한 번 곧바로 다시 보낸다").hasSize(6)
                .allMatch("POST /external/draw-entries"::equals);
        assertThat(draw.deadLetter(queues, NONE)).isEmpty();
    }

    @Test
    void 응답이_정한_지연이_길어도_가장_긴_재시도_간격까지만_늦춘다() {
        MOCK.respond(FakeMock.header("Retry-After", "100000", error(429, "TOO_MANY")));

        assertThat(handler.handle(draw.entry())).isEqualTo(MessageOutcome.returnAfter(Duration.ofMinutes(1)));
    }

    @Test
    void 다시_보내도_같은_거절은_DLQ_에_바로_넣고_지운다() {
        for (String code : new String[]{"CUSTOMER_ALREADY_ENTERED", "DRAW_CLOSED"}) {
            Draws one = new Draws();
            MOCK.respond(error(409, code));

            assertThat(handler.handle(one.entry())).as(code).isEqualTo(MessageOutcome.delete());

            Message dead = one.deadLetter(queues, Draws.TIMEOUT).orElseThrow(() -> new AssertionError(code));
            assertThat(Draws.attributes(dead)).as("받은 속성을 잇고 이유를 더한다")
                    .containsEntry("deadLetterReason", code)
                    .containsEntry("eventType", "DRAW_ENTRY_PAID")
                    .containsEntry("eventId", "evt-" + one.drawId);
            assertThat(Draws.body(dead).get("aggregateId").asString()).isEqualTo(one.entryId.toString());
            assertThat(one.calls(MOCK)).as("다시 보내지 않는다").hasSize(1);
        }
    }

    @Test
    void 받은_응모_수가_결제_완료_수와_같으면_추첨해_결과를_order_에_보낸다() {
        List<String> winners = winners(2);
        MOCK.respond(routes(status(5), drawn(winners)));

        assertThat(handler.handle(draw.requested(5, 2))).isEqualTo(MessageOutcome.delete());

        assertThat(draw.calls(MOCK)).containsExactly("GET /external/draws/{drawId}", "POST /external/draws/{drawId}/draw");
        String drawBody = MOCK.requests().stream().filter(request -> request.path().endsWith("/draw")
                && request.path().contains(draw.drawId.toString())).findFirst().orElseThrow().body();
        assertThat(drawBody).as("기대 수를 실어 Mock 이 잠근 채 다시 센다")
                .isEqualTo("{\"winnerCount\":2,\"expectedEntryCount\":5}");
        Message result = draw.result(queues, Draws.TIMEOUT).orElseThrow(() -> new AssertionError("추첨 결과가 오지 않았다"));
        JsonNode envelope = Draws.body(result);
        assertThat(envelope.get("eventType").asString()).isEqualTo("DRAW_RESULT_READY");
        assertThat(envelope.get("aggregateType").asString()).isEqualTo("DRAW_CAMPAIGN");
        assertThat(envelope.get("aggregateId").asString()).isEqualTo(draw.drawId.toString());
        assertThat(envelope.get("payload").get("winnerEntryIds").valueStream().map(JsonNode::asString).toList())
                .as("Mock 이 뽑은 그대로").isEqualTo(winners);
        assertThat(Draws.attributes(result)).containsEntry("eventType", "DRAW_RESULT_READY")
                .containsEntry("eventId", envelope.get("eventId").asString());
    }

    /** 늦은 응모가 아직 재시도 중일 수 있다. 추첨하지 않고 받은 횟수에 따라 점점 길게 기다린다(바로 DLQ 로 보내지 않는다). */
    @Test
    void 받은_응모_수가_다르면_추첨하지_않고_늦춰_다시_받는다() {
        MOCK.respond(routes(status(4), drawn(winners(2))));

        assertThat(handler.handle(draw.message(draw.requestedBody(5, 2), 1)))
                .isEqualTo(MessageOutcome.returnAfter(Duration.ofMinutes(1)));
        assertThat(handler.handle(draw.message(draw.requestedBody(5, 2), 4)))
                .isEqualTo(MessageOutcome.returnAfter(Duration.ofMinutes(5)));

        assertThat(draw.calls(MOCK)).as("추첨을 맡기지 않는다").containsOnly("GET /external/draws/{drawId}");
        assertThat(draw.result(queues, NONE)).isEmpty();
    }

    @Test
    void 회차에_응모가_아직_없으면_응모_수_0_으로_읽고_기다린다() {
        MOCK.respond(routes(error(404, "NOT_FOUND"), drawn(winners(1))));

        assertThat(handler.handle(draw.requested(3, 1))).isEqualTo(MessageOutcome.returnAfter(Duration.ofMinutes(1)));
        assertThat(draw.calls(MOCK)).containsExactly("GET /external/draws/{drawId}");
    }

    /** 조회와 추첨 사이에 수가 바뀌면 Mock 이 다시 세어 409 로 거절한다 — 확정 거절이 아니라 기다림이다. */
    @Test
    void 추첨이_응모_수_불일치로_거절되면_기다린다() {
        MOCK.respond(routes(status(5), error(409, "ENTRY_COUNT_MISMATCH")));

        assertThat(handler.handle(draw.requested(5, 2))).isEqualTo(MessageOutcome.returnAfter(Duration.ofMinutes(1)));
        assertThat(draw.deadLetter(queues, NONE)).isEmpty();
    }

    @Test
    void 추첨_인원이_잘못됐으면_be_버그라_DLQ_에_넣는다() {
        MOCK.respond(routes(status(5), error(422, "INVALID_WINNER_COUNT")));

        assertThat(handler.handle(draw.requested(5, 2))).isEqualTo(MessageOutcome.delete());

        assertThat(Draws.attributes(draw.deadLetter(queues, Draws.TIMEOUT).orElseThrow()))
                .containsEntry("deadLetterReason", "INVALID_WINNER_COUNT");
        assertThat(draw.result(queues, NONE)).isEmpty();
    }

    @Test
    void 추첨_결과가_당첨_인원과_다르면_order_에_보내지_않고_DLQ_에_넣는다() {
        MOCK.respond(routes(status(5), drawn(winners(3))));

        assertThat(handler.handle(draw.requested(5, 2))).isEqualTo(MessageOutcome.delete());

        assertThat(Draws.attributes(draw.deadLetter(queues, Draws.TIMEOUT).orElseThrow()))
                .containsEntry("deadLetterReason", "INVALID_DRAW_RESULT");
        assertThat(draw.result(queues, NONE)).isEmpty();
    }

    /** 보낸 뒤 지우기 전에 실패해도 된다 — 다시 받으면 Mock 이 처음 결과를 재생하고, order 는 같은 결과를 한 번만 반영한다. */
    @Test
    void 결과를_보내지_못하면_던져_원본을_남기고_다시_받으면_같은_결과를_보낸다() {
        List<String> winners = winners(1);
        MOCK.respond(routes(status(3), drawn(winners)));
        willThrow(new IllegalStateException("send failed")).given(publisher).publishDrawResult(any(), any());

        assertThatThrownBy(() -> handler.handle(draw.requested(3, 1))).hasMessage("send failed");
        assertThat(draw.result(queues, NONE)).isEmpty();

        reset(publisher);
        assertThat(handler.handle(draw.requested(3, 1))).isEqualTo(MessageOutcome.delete());
        assertThat(Draws.body(draw.result(queues, Draws.TIMEOUT).orElseThrow()).get("payload").get("winnerEntryIds")
                .valueStream().map(JsonNode::asString).toList()).isEqualTo(winners);
    }

    /** 주소 설정 오류(없는 경로)는 응모 0건으로 읽지 않는다. 받은 횟수를 쓰지 않게 새 메시지로 돌려보낸다. */
    @Test
    void 없는_경로_응답이면_추첨하지_않고_받은_횟수를_쓰지_않게_돌려보낸다() {
        MOCK.respond(routes(error(404, "NO_SUCH_ENDPOINT"), drawn(winners(1))));

        assertThat(handler.handle(draw.requested(3, 1))).isEqualTo(MessageOutcome.delete());

        Message resent = draw.resent(queues, Duration.ofSeconds(30)).orElseThrow(() -> new AssertionError("돌려보내지 않았다"));
        assertThat(Draws.attributes(resent)).containsEntry("eventType", "DRAW_REQUESTED");
        assertThat(draw.calls(MOCK)).containsOnly("GET /external/draws/{drawId}");
        assertThat(draw.deadLetter(queues, NONE)).isEmpty();
    }

    @Test
    void 모르는_종류는_지우지_않는다() {
        String body = draw.entryBody().replace("DRAW_ENTRY_PAID", "DRAW_SOMETHING_NEW");
        double before = meters.counter(DrawHandler.METRIC, "eventType", "UNKNOWN", "outcome", "unknown-type").count();

        assertThat(handler.handle(draw.message(body, 1))).isEqualTo(MessageOutcome.keep());

        assertThat(meters.counter(DrawHandler.METRIC, "eventType", "UNKNOWN", "outcome", "unknown-type").count())
                .isEqualTo(before + 1);
        assertThat(draw.calls(MOCK)).isEmpty();
    }

    @Test
    void 내용이_계약과_다르면_부르지_않고_DLQ_에_넣는다() {
        String noCustomer = draw.entryBody().replace(",\"customerId\":\"" + draw.customerId + "\"", "");
        String winnersNotFewer = draw.requestedBody(2, 2);
        String noAggregateId = draw.requestedBody(5, 2).replace("\"aggregateId\":\"" + draw.drawId + "\",",
                "\"aggregateId\":null,\"drawId\":\"" + draw.drawId + "\",");

        for (String body : new String[]{noCustomer, winnersNotFewer, noAggregateId}) {
            assertThat(handler.handle(draw.message(body, 1))).as(body).isEqualTo(MessageOutcome.delete());
            assertThat(Draws.attributes(draw.deadLetter(queues, Draws.TIMEOUT).orElseThrow(() -> new AssertionError(body))))
                    .containsEntry("deadLetterReason", "INVALID_MESSAGE");
        }
        assertThat(draw.calls(MOCK)).isEmpty();
    }

    @Test
    void 결과_지표는_처음부터_0_으로_나간다() {
        for (String type : new String[]{"DRAW_ENTRY_PAID", "DRAW_REQUESTED"}) {
            for (String outcome : DrawHandler.OUTCOMES) {
                assertThat(meters.find(DrawHandler.METRIC).tags("eventType", type, "outcome", outcome).counter())
                        .as(type + " " + outcome).isNotNull();
            }
        }
    }
}
