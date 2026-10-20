package com.grandis.nova.worker.sync;

import com.grandis.nova.common.sqs.MessageOutcome;
import com.grandis.nova.common.sqs.BatchQueueConsumer;
import com.grandis.nova.common.sqs.DelayedResend;
import com.grandis.nova.common.sqs.testing.SqsTestConfig;
import com.grandis.nova.common.sqs.testing.TestQueues;
import com.grandis.nova.worker.mock.FakeMock;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import software.amazon.awssdk.services.sqs.model.Message;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.grandis.nova.worker.mock.FakeMock.empty;
import static com.grandis.nova.worker.mock.FakeMock.html;
import static com.grandis.nova.worker.mock.FakeMock.inOrder;
import static com.grandis.nova.worker.mock.FakeMock.json;
import static com.grandis.nova.worker.sync.Jobs.attempt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willThrow;

/**
 * 등록 작업 메시지 하나의 한 차례. 가짜 mock 에 실제 HTTP 로 부르고, 결과 이벤트 · 다시 보낸 메시지는 실제 SQS(Floci)에서 꺼내 본다.
 * 큐 소비기는 대역으로 바꾸고 처리기를 직접 불러, 차례마다 무엇을 내보내는지를 지연을 기다리지 않고 본다.
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
class JobHandlerTest {

    static final FakeMock MOCK = new FakeMock();
    static final String CREATED = "{\"externalKey\":\"K\",\"externalNumber\":\"R-1\",\"status\":\"ACTIVE\"}";

    @Autowired
    @Qualifier("registerJobHandler")
    JobHandler handler;

    @Autowired
    TestQueues queues;

    /** 소비기가 큐를 받지 않게 대역으로 바꾼다. 처리기는 시험이 직접 부른다. */
    @MockitoBean
    BatchQueueConsumer registerConsumer;

    @MockitoBean
    BatchQueueConsumer cancelConsumer;

    @Autowired
    MeterRegistry meters;

    @MockitoSpyBean
    ResultPublisher publisher;

    @MockitoSpyBean
    DelayedResend registerResend;

    final Jobs job = new Jobs();

    @DynamicPropertySource
    static void mock(DynamicPropertyRegistry registry) {
        registry.add("nova.worker.mock.base-url", () -> MOCK.baseUrl().toString());
    }

    @AfterAll
    static void closeMock() {
        MOCK.close();
    }

    @Test
    void 성공하면_성공_이벤트를_보내고_원본을_지운다() {
        MOCK.respond(json(201, CREATED));
        Instant before = Instant.now();

        MessageOutcome outcome = handler.handle(job.message(Map.of("run", "3", "actor", "ADMIN")));

        assertThat(outcome).isEqualTo(MessageOutcome.delete());
        JsonNode event = job.event(queues);
        assertThat(event.get("eventType").asString()).isEqualTo("EXTERNAL_JOB_SUCCEEDED");
        assertThat(event.get("aggregateType").asString()).isEqualTo("PREORDER_SYNC_JOB");
        assertThat(event.get("aggregateId").asString()).isEqualTo(job.syncJobId.toString());
        assertThat(Instant.parse(event.get("occurredAt").asString())).isAfterOrEqualTo(before.minusSeconds(1));
        JsonNode payload = event.get("payload");
        assertThat(payload.get("syncJobId").asString()).isEqualTo(job.syncJobId.toString());
        assertThat(payload.get("preorderId").asString()).isEqualTo(job.key);
        assertThat(payload.get("jobType").asString()).isEqualTo("REGISTER");
        assertThat(payload.get("externalNumber").asString()).isEqualTo("R-1");
        assertThat(attempt(payload)).isEqualTo("run=3 1-1 ADMIN SUCCESS 201 null");
        assertThat(job.calls(MOCK)).containsExactly("POST /external/reservations");
        assertThat(MOCK.requests().stream().filter(request -> job.key.equals(request.idempotencyKey())).findFirst()
                .orElseThrow().body()).as("메시지에 실려 온 내용 그대로 보낸다")
                .isEqualTo("{\"ourReservationId\":\"" + job.key + "\",\"qty\":1}");
        assertThat(job.resent(queues, Duration.ofSeconds(2))).as("다시 보내지 않는다").isEmpty();
    }

    /** 저장되지 않은 것이 확실한 실패(503)는 같은 차례 안에서 한 번 더 보낸다. 그래도 실패하면 다음 차례를 잡는다. */
    @Test
    void 일시_실패가_이어지면_차례를_기록하고_다음_차례로_지연을_두고_다시_보낸다() {
        MOCK.respond(html(503));

        handler.handle(job.message(Map.of("run", "2", "actor", "ADMIN", "eventId", "evt-1")));

        JsonNode event = job.event(queues);
        assertThat(event.get("eventType").asString()).isEqualTo("EXTERNAL_JOB_ATTEMPTED");
        assertThat(attempt(event.get("payload"))).isEqualTo("run=2 1-1 ADMIN TRANSIENT_FAILURE 503 null");
        assertThat(job.calls(MOCK)).hasSize(2);
        assertThat(job.resent(queues)).isEqualTo(Map.of("run", "2", "attempt", "2", "generation", "1",
                "lastOutcome", "TRANSIENT_FAILURE", "actor", "ADMIN", "eventType", "REGISTER_JOB_READY",
                "eventId", "evt-1"));
    }

    @Test
    void 일시_실패_뒤_곧바로_다시_보내_성공하면_한_차례로_끝난다() {
        MOCK.respond(inOrder(html(503), json(201, CREATED)));

        handler.handle(job.message(Map.of()));

        JsonNode event = job.event(queues);
        assertThat(event.get("eventType").asString()).isEqualTo("EXTERNAL_JOB_SUCCEEDED");
        assertThat(attempt(event.get("payload"))).isEqualTo("run=1 1-1 SYSTEM SUCCESS 201 null");
        JsonNode attempt = event.get("payload").get("attempt");
        assertThat(Instant.parse(attempt.get("startedAt").asString())).as("차례의 첫 호출부터 마지막 응답까지")
                .isBefore(Instant.parse(attempt.get("finishedAt").asString()));
        assertThat(job.calls(MOCK)).hasSize(2);
    }

    /** 저장됐는지 모르는 실패(504)는 곧바로 다시 보내지 않고, 다음 차례가 조회부터 하도록 표시한다. */
    @Test
    void 결과를_모르면_곧바로_다시_보내지_않고_다음_차례에_조회하게_표시한다() {
        MOCK.respond(html(504));

        handler.handle(job.message(Map.of()));

        assertThat(attempt(job.event(queues).get("payload"))).isEqualTo("run=1 1-1 SYSTEM UNKNOWN 504 null");
        assertThat(job.calls(MOCK)).hasSize(1);
        assertThat(job.resent(queues)).containsEntry("attempt", "2").containsEntry("lastOutcome", "UNKNOWN");
    }

    @Test
    void 직전_결과를_모르면_조회부터_하고_등록돼_있으면_다시_보내지_않고_성공으로_끝낸다() {
        MOCK.respond(json(200, "{\"registrations\":[{\"externalNumber\":\"R-9\",\"status\":\"ACTIVE\"}]}"));

        handler.handle(job.message(Map.of("attempt", "2", "lastOutcome", "UNKNOWN")));

        JsonNode payload = job.event(queues).get("payload");
        assertThat(payload.get("externalNumber").asString()).isEqualTo("R-9");
        assertThat(attempt(payload)).isEqualTo("run=1 1-2 SYSTEM SUCCESS 200 null");
        assertThat(job.calls(MOCK)).containsExactly("GET /external/reservations/by-key/{key}");
    }

    @Test
    void 조회해서_등록이_없으면_같은_키로_등록한다() {
        MOCK.respond(inOrder(json(404, "{\"errorCode\":\"NOT_FOUND\"}"), json(201, CREATED)));

        handler.handle(job.message(Map.of("attempt", "2", "lastOutcome", "UNKNOWN")));

        assertThat(job.event(queues).get("eventType").asString()).isEqualTo("EXTERNAL_JOB_SUCCEEDED");
        assertThat(job.calls(MOCK)).containsExactly("GET /external/reservations/by-key/{key}",
                "POST /external/reservations");
    }

    /** 조회도 실패하면 등록됐는지 여전히 모른다 — 등록을 보내지 않고 다음 차례도 조회부터 한다. */
    @Test
    void 조회도_실패하면_등록을_보내지_않고_결과_모름으로_다음_차례를_잡는다() {
        MOCK.respond(empty(500));

        handler.handle(job.message(Map.of("attempt", "2", "lastOutcome", "UNKNOWN")));

        assertThat(attempt(job.event(queues).get("payload"))).isEqualTo("run=1 1-2 SYSTEM UNKNOWN 500 null");
        assertThat(job.calls(MOCK)).containsExactly("GET /external/reservations/by-key/{key}");
        assertThat(job.resent(queues)).containsEntry("attempt", "3").containsEntry("lastOutcome", "UNKNOWN");
    }

    @Test
    void 조회해서_취소_표식이_있으면_실패로_끝낸다() {
        MOCK.respond(json(200, "{\"registrations\":[],\"cancelMarkerAt\":\"2026-10-12T01:00:00Z\"}"));

        handler.handle(job.message(Map.of("attempt", "2", "lastOutcome", "UNKNOWN")));

        JsonNode event = job.event(queues);
        assertThat(event.get("eventType").asString()).isEqualTo("EXTERNAL_JOB_FAILED");
        assertThat(event.get("payload").get("reason").asString()).isEqualTo("KEY_CANCELED");
        assertThat(job.calls(MOCK)).hasSize(1);
    }

    @Test
    void 외부가_거절하면_다시_보내지_않고_거절한_이유로_실패를_알린다() {
        MOCK.respond(json(422, "{\"errorCode\":\"KEY_PAYLOAD_MISMATCH\",\"errorMessage\":\"다른 내용\"}"));

        MessageOutcome outcome = handler.handle(job.message(Map.of()));

        assertThat(outcome).isEqualTo(MessageOutcome.delete());
        JsonNode event = job.event(queues);
        assertThat(event.get("eventType").asString()).isEqualTo("EXTERNAL_JOB_FAILED");
        assertThat(event.get("payload").get("reason").asString()).isEqualTo("KEY_PAYLOAD_MISMATCH");
        assertThat(attempt(event.get("payload"))).isEqualTo("run=1 1-1 SYSTEM REJECTED 422 KEY_PAYLOAD_MISMATCH");
        assertThat(event.get("payload").get("attempt").get("errorMessage").asString()).isEqualTo("다른 내용");
        assertThat(job.calls(MOCK)).hasSize(1);
        assertThat(job.resent(queues, Duration.ofSeconds(2))).isEmpty();
    }

    @Test
    void 세대의_마지막_차례가_실패하면_다음_세대의_첫_차례로_다시_보낸다() {
        MOCK.respond(html(503));

        handler.handle(job.message(Map.of("attempt", "4", "generation", "1", "lastOutcome", "TRANSIENT_FAILURE")));

        JsonNode event = job.event(queues);
        assertThat(event.get("eventType").asString()).isEqualTo("EXTERNAL_JOB_ATTEMPTED");
        assertThat(attempt(event.get("payload"))).isEqualTo("run=1 1-4 SYSTEM TRANSIENT_FAILURE 503 null");
        assertThat(job.resent(queues)).containsEntry("generation", "2").containsEntry("attempt", "1")
                .containsEntry("lastOutcome", "TRANSIENT_FAILURE");
    }

    /** 포기 이벤트에는 마지막 차례를 싣는다 — preorder 가 그 기록으로 이미 받은 이벤트 · 지난 실행의 결과를 가린다. */
    @Test
    void 마지막_세대의_마지막_차례가_실패하면_마지막_차례를_실어_포기를_알리고_다시_보내지_않는다() {
        MOCK.respond(html(503));

        handler.handle(job.message(Map.of("run", "2", "attempt", "4", "generation", "3")));

        JsonNode event = job.event(queues);
        assertThat(event.get("eventType").asString()).isEqualTo("EXTERNAL_JOB_FAILED");
        assertThat(event.get("payload").get("reason").asString()).isEqualTo("GENERATION_EXHAUSTED");
        assertThat(attempt(event.get("payload"))).isEqualTo("run=2 3-4 SYSTEM TRANSIENT_FAILURE 503 null");
        assertThat(job.event(queues, Duration.ofSeconds(2))).as("차례 기록 이벤트를 따로 보내지 않는다").isEmpty();
        assertThat(job.resent(queues, Duration.ofSeconds(2))).isEmpty();
    }

    /** 외부를 부르지 않았지만 포기는 알려야 한다 — 부르지 않은 차례(응답 상태 없음)를 만들어 싣는다. */
    @Test
    void 보낼_내용이_없는_메시지는_외부를_부르지_않고_포기를_알린다() {
        Message broken = job.message(Map.of("run", "2")).toBuilder().body(job.body("")).build();

        MessageOutcome outcome = handler.handle(broken);

        assertThat(outcome).isEqualTo(MessageOutcome.delete());
        JsonNode payload = job.event(queues).get("payload");
        assertThat(payload.get("reason").asString()).isEqualTo("PAYLOAD_INVALID");
        assertThat(payload.get("preorderId").asString()).isEqualTo(job.key);
        assertThat(attempt(payload)).isEqualTo("run=2 1-1 SYSTEM REJECTED null PAYLOAD_INVALID");
        assertThat(job.calls(MOCK)).isEmpty();
    }

    /** 누구의 작업인지 알 수 없어 결과를 알릴 수도 없다. 지우면 원본이 사라지므로 남겨 두어 끝내 DLQ 로 가게 한다. */
    @Test
    void 읽을_수_없는_메시지는_지우지_않고_지표로_알린다() {
        double before = count("unreadable");

        assertThat(handler.handle(job.message(Map.of()).toBuilder().body("not-json").build()))
                .isEqualTo(MessageOutcome.keep());
        assertThat(handler.handle(job.message(Map.of()).toBuilder()
                .body("{\"eventType\":\"REGISTER_JOB_READY\",\"payload\":{\"preorderId\":\"x\"}}").build()))
                .isEqualTo(MessageOutcome.keep());

        assertThat(count("unreadable") - before).isEqualTo(2);
    }

    /** mock 이 아닌 곳의 응답(주소 · 경로 설정 오류)이다. 우리 쪽을 고치면 되므로 차례를 쓰지 않고, 처음 본 시각을 남긴다. */
    @Test
    void 설정_오류면_차례를_쓰지_않고_처음_본_시각을_실어_다시_보낸다() {
        MOCK.respond(html(404));
        Instant before = Instant.now();

        handler.handle(job.message(Map.of("attempt", "2", "generation", "2", "lastOutcome", "TRANSIENT_FAILURE")));

        Map<String, String> resent = job.resent(queues);
        assertThat(resent).containsEntry("attempt", "2").containsEntry("generation", "2")
                .containsEntry("lastOutcome", "TRANSIENT_FAILURE").containsEntry("eventType", "REGISTER_JOB_READY");
        Instant since = Instant.parse(resent.get("misconfiguredSince"));
        assertThat(since).isAfterOrEqualTo(before.minusSeconds(1));
        assertThat(job.event(queues, Duration.ofSeconds(2))).as("시도로 세지 않는다").isEmpty();

        handler.handle(job.message(resent));

        assertThat(job.resent(queues)).as("처음 본 시각을 그대로 잇는다").containsEntry("misconfiguredSince", since.toString());
    }

    /** 돌려보낸 메시지는 새 메시지라 큐 나이 경보에 잡히지 않는다. 처음 본 때부터 지난 시간을 지표로 내 포기 전에 본다. */
    @Test
    void 설정_오류로_돌려보낼_때마다_처음_본_때부터_지난_시간을_지표로_낸다() {
        MOCK.respond(html(404));
        Timer held = meters.timer(JobHandler.MISCONFIGURED_METRIC, "jobType", "REGISTER");
        long before = held.count();
        Instant since = Instant.now().minus(Duration.ofMinutes(10));

        handler.handle(job.message(Map.of("misconfiguredSince", since.toString())));

        assertThat(job.resent(queues)).containsKey("misconfiguredSince");
        assertThat(held.count() - before).isEqualTo(1);
        assertThat(held.max(TimeUnit.MINUTES)).isGreaterThanOrEqualTo(10);
    }

    /** 끝없이 돌면 작업이 PENDING 에 남은 채 아무 경보에도 잡히지 않는다. 정한 시간(기본 30분)이 지나면 포기를 알린다. */
    @Test
    void 설정_오류가_정한_시간_넘게_이어지면_포기를_알린다() {
        MOCK.respond(html(404));
        String since = Instant.now().minus(Duration.ofMinutes(31)).toString();

        MessageOutcome outcome = handler.handle(job.message(Map.of("run", "2", "attempt", "3",
                "misconfiguredSince", since)));

        assertThat(outcome).isEqualTo(MessageOutcome.delete());
        JsonNode event = job.event(queues);
        assertThat(event.get("eventType").asString()).isEqualTo("EXTERNAL_JOB_FAILED");
        assertThat(event.get("payload").get("reason").asString()).isEqualTo("MISCONFIGURED");
        assertThat(attempt(event.get("payload"))).isEqualTo("run=2 1-3 SYSTEM REJECTED 404 MISCONFIGURED");
        assertThat(job.resent(queues, Duration.ofSeconds(2))).isEmpty();
    }

    /** 차례를 쓰면 설정 오류는 풀린 것이다. 시각을 이으면 다음 설정 오류에서 바로 포기한다. */
    @Test
    void 차례를_쓰고_다시_보낼_때는_설정_오류_시각을_뺀다() {
        MOCK.respond(html(503));

        handler.handle(job.message(Map.of("misconfiguredSince", Instant.now().toString())));

        assertThat(job.resent(queues)).doesNotContainKey("misconfiguredSince").containsEntry("attempt", "2");
    }

    /** 결과를 알리지 못했는데 다음으로 넘어가면 preorder 는 그 차례를 영영 모른다. 던져서 원본을 남긴다. */
    @Test
    void 결과_이벤트를_보내지_못하면_다시_보내지도_지우지도_않는다() {
        MOCK.respond(html(503));
        willThrow(new IllegalStateException("sqs down")).given(publisher).publish(any(), any());

        assertThatThrownBy(() -> handler.handle(job.message(Map.of()))).isInstanceOf(IllegalStateException.class);

        assertThat(job.resent(queues, Duration.ofSeconds(2))).isEmpty();
    }

    /** 알린 뒤 다시 보내지 못하면 원본이 남아 같은 차례를 다시 한다. 차례 기록은 같은 번호로 한 번 더 나간다. */
    @Test
    void 결과를_알린_뒤_다시_보내지_못하면_던지고_다시_받아_같은_차례를_다시_한다() {
        MOCK.respond(html(503));
        Message message = job.message(Map.of("run", "2"));
        willThrow(new IllegalStateException("sqs down")).willCallRealMethod()
                .given(registerResend).resend(any(), any(), any());

        assertThatThrownBy(() -> handler.handle(message)).isInstanceOf(IllegalStateException.class);
        JsonNode first = job.event(queues);
        assertThat(job.resent(queues, Duration.ofSeconds(2))).as("다음 차례로 넘어가지 않았다").isEmpty();

        assertThat(handler.handle(message)).isEqualTo(MessageOutcome.delete());
        JsonNode second = job.event(queues);

        assertThat(attempt(first.get("payload"))).isEqualTo("run=2 1-1 SYSTEM TRANSIENT_FAILURE 503 null");
        assertThat(attempt(second.get("payload"))).isEqualTo(attempt(first.get("payload")));
        assertThat(second.get("eventId").asString()).as("같은 차례의 같은 결과라 preorder 가 이미 받은 것으로 가린다")
                .isEqualTo(first.get("eventId").asString());
        assertThat(second.get("eventType").asString()).isEqualTo("EXTERNAL_JOB_ATTEMPTED");
        assertThat(job.resent(queues)).containsEntry("run", "2").containsEntry("generation", "1")
                .containsEntry("attempt", "2");
    }

    /** 원본을 지우지 못했거나 가시성 시간이 지나 같은 메시지를 또 받는다. 외부는 같은 키 · 같은 내용이어야 같은 등록으로 본다. */
    @Test
    void 같은_메시지를_두_번_받으면_같은_키와_같은_내용으로_다시_보낸다() {
        MOCK.respond(json(201, CREATED));
        Message message = job.message(Map.of());

        handler.handle(message);
        handler.handle(message);

        assertThat(job.calls(MOCK)).containsExactly("POST /external/reservations", "POST /external/reservations");
        assertThat(MOCK.requests().stream().filter(request -> job.key.equals(request.idempotencyKey()))
                .map(FakeMock.Received::body).distinct().toList())
                .containsExactly("{\"ourReservationId\":\"" + job.key + "\",\"qty\":1}");
        assertThat(attempt(job.event(queues).get("payload"))).isEqualTo("run=1 1-1 SYSTEM SUCCESS 201 null");
        assertThat(attempt(job.event(queues).get("payload"))).isEqualTo("run=1 1-1 SYSTEM SUCCESS 201 null");
    }

    /** 한 번도 일어나지 않은 결과도 0 으로 나가야 경보가 "값 없음"을 따로 다루지 않는다. */
    @Test
    void 처리_결과_지표는_일어나기_전부터_종류별로_있다() {
        for (String jobType : List.of("REGISTER", "CANCEL")) {
            for (String outcome : JobHandler.OUTCOMES) {
                assertThat(meters.find(JobHandler.JOBS_METRIC).tags("jobType", jobType, "outcome", outcome).counter())
                        .as(jobType + " " + outcome).isNotNull();
            }
            assertThat(meters.find(JobHandler.GENERATIONS_METRIC).tags("jobType", jobType).counter()).isNotNull();
            assertThat(meters.find(JobHandler.MISCONFIGURED_METRIC).tags("jobType", jobType).timer()).isNotNull();
        }
    }

    private double count(String outcome) {
        return meters.counter(JobHandler.JOBS_METRIC, "jobType", "REGISTER", "outcome", outcome).count();
    }
}
