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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.Map;

import static com.grandis.nova.worker.mock.FakeMock.html;
import static com.grandis.nova.worker.mock.FakeMock.inOrder;
import static com.grandis.nova.worker.mock.FakeMock.json;
import static com.grandis.nova.worker.sync.Jobs.attempt;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 취소 작업 메시지 하나의 한 차례. 등록과 달리 결과를 몰라도 조회하지 않고, 세대를 다 써도 포기하지 않는다.
 * 큐 소비기는 대역으로 바꾸고 처리기를 직접 부른다.
 */
@SpringBootTest(properties = {
        "nova.sqs.region=ap-northeast-2",
        "nova.worker.mock.connect-timeout=300ms",
        "nova.worker.mock.read-timeout=300ms",
        "nova.worker.mock.slow-call-threshold=150ms",
        "nova.worker.retry.in-round-delay-min=10ms",
        "nova.worker.retry.in-round-delay-max=20ms",
        "nova.worker.retry.backoff=1s,1s,1s",
        "nova.worker.retry.generation-gap=1s",
        "nova.worker.retry.jitter-ratio=0",
        // 이 시험의 실패가 차단기 · 연쇄 거절 차단을 건드리지 않게 문턱을 높인다
        "nova.worker.circuit.window=100",
        "nova.worker.circuit.minimum-calls=100",
        "nova.worker.reject-guard.invalid-requests=100"})
@Import(SqsTestConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CancelJobHandlerTest {

    static final FakeMock MOCK = new FakeMock();
    static final String CANCEL = "POST /external/cancellations";

    @Autowired
    @Qualifier("cancelJobHandler")
    JobHandler handler;

    @Autowired
    TestQueues queues;

    @Autowired
    MeterRegistry meters;

    @MockitoBean
    BatchQueueConsumer registerConsumer;

    @MockitoBean
    BatchQueueConsumer cancelConsumer;

    final Jobs job = Jobs.cancel();

    @DynamicPropertySource
    static void mock(DynamicPropertyRegistry registry) {
        registry.add("nova.worker.mock.base-url", () -> MOCK.baseUrl().toString());
    }

    @AfterAll
    static void closeMock() {
        MOCK.close();
    }

    @Test
    void 취소되면_성공_이벤트를_보내고_원본을_지운다() {
        MOCK.respond(json(200, "{\"externalNumbers\":[\"R-3\"]}"));

        MessageOutcome outcome = handler.handle(job.message(Map.of()));

        assertThat(outcome).isEqualTo(MessageOutcome.delete());
        JsonNode event = job.event(queues);
        assertThat(event.get("eventType").asString()).isEqualTo("EXTERNAL_JOB_SUCCEEDED");
        JsonNode payload = event.get("payload");
        assertThat(payload.get("jobType").asString()).isEqualTo("CANCEL");
        assertThat(payload.get("preorderId").asString()).isEqualTo(job.key);
        assertThat(payload.get("externalNumber").asString()).isEqualTo("R-3");
        assertThat(attempt(payload)).isEqualTo("run=1 1-1 SYSTEM SUCCESS 200 null");
        assertThat(job.calls(MOCK)).containsExactly(CANCEL);
        assertThat(MOCK.requests().stream().filter(request -> request.body().contains(job.key)).findFirst()
                .orElseThrow().body()).as("메시지에 실려 온 내용 그대로 보낸다")
                .isEqualTo("{\"externalKey\":\"" + job.key + "\",\"reason\":\"USER_CANCEL\"}");
        assertThat(job.resent(queues, Duration.ofSeconds(2))).as("다시 보내지 않는다").isEmpty();
    }

    /** 외부가 모르는 키여도 200 이다 — 취소 표식이 남아 뒤늦은 등록을 막는다. 취소한 번호는 없다. */
    @Test
    void 외부에_등록이_없어도_성공으로_끝낸다() {
        MOCK.respond(json(200, "{\"externalNumbers\":[]}"));

        handler.handle(job.message(Map.of()));

        JsonNode payload = job.event(queues).get("payload");
        assertThat(payload.get("externalNumber").isNull()).isTrue();
        assertThat(attempt(payload)).isEqualTo("run=1 1-1 SYSTEM SUCCESS 200 null");
    }

    @Test
    void 외부가_거절하면_다시_보내지_않고_거절한_이유로_실패를_알린다() {
        MOCK.respond(json(400, "{\"errorCode\":\"INVALID_REQUEST\",\"errorMessage\":\"키와 번호가 다르다\"}"));

        MessageOutcome outcome = handler.handle(job.message(Map.of("run", "2")));

        assertThat(outcome).isEqualTo(MessageOutcome.delete());
        JsonNode event = job.event(queues);
        assertThat(event.get("eventType").asString()).isEqualTo("EXTERNAL_JOB_FAILED");
        assertThat(event.get("payload").get("jobType").asString()).isEqualTo("CANCEL");
        assertThat(event.get("payload").get("reason").asString()).isEqualTo("INVALID_REQUEST");
        assertThat(attempt(event.get("payload"))).isEqualTo("run=2 1-1 SYSTEM REJECTED 400 INVALID_REQUEST");
        assertThat(job.calls(MOCK)).containsExactly(CANCEL);
        assertThat(job.resent(queues, Duration.ofSeconds(2))).isEmpty();
    }

    /** 번호만 보냈을 때 나오는 응답이다. 우리는 늘 키를 보내므로 생기면 보낸 내용이 잘못된 것 — 다시 보내도 같다. */
    @Test
    void 외부가_없다고_답하면_실패로_끝낸다() {
        MOCK.respond(json(404, "{\"errorCode\":\"NOT_FOUND\"}"));

        handler.handle(job.message(Map.of()));

        JsonNode event = job.event(queues);
        assertThat(event.get("eventType").asString()).isEqualTo("EXTERNAL_JOB_FAILED");
        assertThat(event.get("payload").get("reason").asString()).isEqualTo("NOT_FOUND");
        assertThat(job.resent(queues, Duration.ofSeconds(2))).isEmpty();
    }

    @Test
    void 일시_실패가_이어지면_곧바로_한_번_더_보낸_뒤_다음_차례로_지연을_두고_다시_보낸다() {
        MOCK.respond(html(503));

        handler.handle(job.message(Map.of("actor", "ADMIN")));

        JsonNode event = job.event(queues);
        assertThat(event.get("eventType").asString()).isEqualTo("EXTERNAL_JOB_ATTEMPTED");
        assertThat(attempt(event.get("payload"))).isEqualTo("run=1 1-1 ADMIN TRANSIENT_FAILURE 503 null");
        assertThat(job.calls(MOCK)).containsExactly(CANCEL, CANCEL);
        assertThat(job.resent(queues)).isEqualTo(Map.of("run", "1", "attempt", "2", "generation", "1",
                "lastOutcome", "TRANSIENT_FAILURE", "actor", "ADMIN", "eventType", "CANCEL_JOB_READY"));
    }

    @Test
    void 일시_실패_뒤_곧바로_다시_보내_취소되면_한_차례로_끝난다() {
        MOCK.respond(inOrder(html(503), json(200, "{\"externalNumbers\":[\"R-3\"]}")));

        handler.handle(job.message(Map.of()));

        assertThat(attempt(job.event(queues).get("payload"))).isEqualTo("run=1 1-1 SYSTEM SUCCESS 200 null");
        assertThat(job.calls(MOCK)).hasSize(2);
    }

    /** 취소는 여러 번 보내도 결과가 같다. 등록처럼 조회로 확인하지 않는다. */
    @Test
    void 직전_결과를_몰라도_조회하지_않고_다시_보낸다() {
        MOCK.respond(json(200, "{\"externalNumbers\":[\"R-3\"]}"));

        handler.handle(job.message(Map.of("attempt", "2", "lastOutcome", "UNKNOWN")));

        assertThat(job.calls(MOCK)).containsExactly(CANCEL);
        assertThat(attempt(job.event(queues).get("payload"))).isEqualTo("run=1 1-2 SYSTEM SUCCESS 200 null");
    }

    /** 등록이라면 포기했을 자리다. 취소는 외부에 등록을 남길 수 없으므로 다음 세대로 넘기고 지표로 알린다. */
    @Test
    void 마지막_세대의_마지막_차례가_실패해도_포기하지_않고_다음_세대로_넘긴다() {
        MOCK.respond(html(503));
        double before = meters.counter(JobHandler.GENERATIONS_METRIC, "jobType", "CANCEL").count();

        handler.handle(job.message(Map.of("attempt", "4", "generation", "3")));

        JsonNode event = job.event(queues);
        assertThat(event.get("eventType").asString()).isEqualTo("EXTERNAL_JOB_ATTEMPTED");
        assertThat(attempt(event.get("payload"))).isEqualTo("run=1 3-4 SYSTEM TRANSIENT_FAILURE 503 null");
        assertThat(job.resent(queues)).containsEntry("generation", "4").containsEntry("attempt", "1");
        assertThat(meters.counter(JobHandler.GENERATIONS_METRIC, "jobType", "CANCEL").count() - before).isEqualTo(1);
    }

    @Test
    void 설정_오류면_차례를_쓰지_않고_속성_그대로_다시_보낸다() {
        MOCK.respond(html(404));

        handler.handle(job.message(Map.of("attempt", "2", "generation", "5")));

        assertThat(job.resent(queues)).isEqualTo(Map.of("attempt", "2", "generation", "5",
                "eventType", "CANCEL_JOB_READY"));
        assertThat(job.event(queues, Duration.ofSeconds(2))).as("시도로 세지 않는다").isEmpty();
    }
}
