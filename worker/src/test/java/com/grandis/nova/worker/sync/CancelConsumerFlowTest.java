package com.grandis.nova.worker.sync;

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
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.grandis.nova.worker.mock.FakeMock.after;
import static com.grandis.nova.worker.mock.FakeMock.html;
import static com.grandis.nova.worker.mock.FakeMock.inOrder;
import static com.grandis.nova.worker.mock.FakeMock.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 취소 큐에 넣은 작업이 소비기 → 외부 취소 → 결과 이벤트로 이어지는지, 두 큐의 소비기가 자리를 나눠 쓰는지 본다(Floci · 가짜 mock).
 * 자리는 2개이고 하나는 취소 전용이다.
 */
@SpringBootTest(properties = {
        "nova.sqs.region=ap-northeast-2",
        "nova.worker.consumer.wait-seconds=1",
        // 등록 응답을 시험이 풀어 줄 때까지 붙잡아 둔다
        "nova.worker.mock.connect-timeout=4s",
        "nova.worker.mock.read-timeout=15s",
        "nova.worker.mock.slow-call-threshold=14s",
        "nova.worker.retry.in-round-delay-min=10ms",
        "nova.worker.retry.in-round-delay-max=20ms",
        "nova.worker.retry.backoff=1s,1s,1s",
        "nova.worker.retry.jitter-ratio=0",
        "nova.worker.concurrency.per-mock=2",
        "nova.worker.concurrency.initial=2",
        "nova.worker.concurrency.min=2",
        "nova.worker.circuit.window=100",
        "nova.worker.circuit.minimum-calls=100"})
@Import(SqsTestConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CancelConsumerFlowTest {

    static final FakeMock MOCK = new FakeMock();
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final FakeMock.Handler CREATED =
            json(201, "{\"externalKey\":\"K\",\"externalNumber\":\"R-7\",\"status\":\"ACTIVE\"}");
    static final FakeMock.Handler CANCELED = json(200, "{\"externalNumbers\":[\"R-7\"]}");

    @Autowired
    SqsClient sqs;

    @Autowired
    TestQueues queues;

    @Autowired
    MeterRegistry meters;

    final List<JsonNode> received = new ArrayList<>();

    @DynamicPropertySource
    static void mock(DynamicPropertyRegistry registry) {
        registry.add("nova.worker.mock.base-url", () -> MOCK.baseUrl().toString());
    }

    @AfterAll
    static void closeMock() {
        MOCK.close();
    }

    /**
     * 받은 메시지는 곧 자리를 잡으러 온다. 취소 소비기가 자기 건수만 보고 받으면, 등록이 쓰는 자리까지 받아 와 자리를 못 얻고
     * 큐로 돌려보내게 된다 — 두 소비기의 처리 중 건수를 합쳐서 보면 받은 것은 모두 곧바로 나간다.
     */
    @Test
    void 등록이_처리_중이면_취소는_남은_자리만큼만_받아_돌려보내는_일_없이_모두_끝난다() {
        CountDownLatch registered = new CountDownLatch(1);
        MOCK.respond(exchange -> {
            if (exchange.getRequestURI().getPath().endsWith("/cancellations")) {
                // 자리를 기다리는 시간(permit-wait)보다 오래 쥐고 있어야 넘치게 받은 메시지가 드러난다
                after(Duration.ofMillis(300), CANCELED).handle(exchange);
            } else {
                registered.await(12, TimeUnit.SECONDS);
                CREATED.handle(exchange);
            }
        });
        Jobs register = new Jobs();
        Jobs first = Jobs.cancel();
        List<Jobs> cancels = List.of(Jobs.cancel(), Jobs.cancel(), Jobs.cancel());
        double heldBefore = held("CANCEL") + held("REGISTER");

        try {
            send(register);
            await().atMost(Duration.ofSeconds(10)).until(() -> !register.calls(MOCK).isEmpty());
            // 등록을 받기 전에 건수를 정해 둔 수신이 남아 있을 수 있다. 한 건을 먼저 흘려 그 수신을 끝낸다
            send(first);
            events(first, 1);
            cancels.forEach(this::send);

            for (Jobs cancel : cancels) {
                JsonNode event = events(cancel, 1).getFirst();
                assertThat(event.get("eventType").asString()).isEqualTo("EXTERNAL_JOB_SUCCEEDED");
                assertThat(event.get("payload").get("jobType").asString()).isEqualTo("CANCEL");
                assertThat(cancel.calls(MOCK)).as("한 번만 불렀다").hasSize(1);
            }
            assertThat(held("CANCEL") + held("REGISTER") - heldBefore).as("자리를 못 얻어 돌려보낸 메시지가 없다").isZero();
        } finally {
            registered.countDown();
        }
        assertThat(events(register, 1).getFirst().get("eventType").asString()).isEqualTo("EXTERNAL_JOB_SUCCEEDED");
    }

    @Test
    void 실패한_차례를_알리고_지연_뒤_다시_받아_같은_내용으로_취소까지_이어진다() {
        MOCK.respond(inOrder(html(503), html(503), CANCELED));
        Jobs job = Jobs.cancel();

        send(job);

        List<JsonNode> events = events(job, 2);
        assertThat(events).extracting(event -> event.get("eventType").asString())
                .containsExactly("EXTERNAL_JOB_ATTEMPTED", "EXTERNAL_JOB_SUCCEEDED");
        assertThat(events).extracting(event -> event.get("payload").get("attempt").get("attempt").asInt())
                .as("다음 차례에서 취소됐다").containsExactly(1, 2);
        assertThat(job.calls(MOCK)).hasSize(3);
        assertThat(bodies(job)).as("몇 번을 보내도 같은 내용").hasSize(1);
        await().atMost(Duration.ofSeconds(10)).until(() -> queues.messagesIn(Jobs.CANCEL_QUEUE) == 0);
    }

    /** 원본을 지우지 못했거나 preorder 가 두 번 내보낸 경우다. 취소는 여러 번 보내도 같으므로 둘 다 보내고 둘 다 지운다. */
    @Test
    void 같은_작업을_두_번_받으면_같은_내용으로_두_번_취소하고_둘_다_지운다() {
        MOCK.respond(CANCELED);
        Jobs job = Jobs.cancel();

        send(job);
        send(job);

        assertThat(events(job, 2)).extracting(event -> event.get("eventType").asString())
                .containsExactly("EXTERNAL_JOB_SUCCEEDED", "EXTERNAL_JOB_SUCCEEDED");
        assertThat(job.calls(MOCK)).hasSize(2);
        assertThat(bodies(job)).hasSize(1);
        await().atMost(Duration.ofSeconds(10)).until(() -> queues.messagesIn(Jobs.CANCEL_QUEUE) == 0);
    }

    /** 이 작업의 결과 이벤트를 count 건까지 온 순서대로 모은다. 받은 것은 모두 지운다 — 남기면 받은 횟수가 차 DLQ 로 간다. */
    private List<JsonNode> events(Jobs job, int count) {
        String url = sqs.getQueueUrl(request -> request.queueName(Jobs.EVENTS_QUEUE)).queueUrl();
        String id = job.syncJobId.toString();
        await().atMost(Duration.ofSeconds(20)).until(() -> {
            for (Message message : sqs.receiveMessage(request -> request.queueUrl(url).waitTimeSeconds(1)
                    .maxNumberOfMessages(10)).messages()) {
                received.add(JSON.readTree(message.body()));
                sqs.deleteMessage(request -> request.queueUrl(url).receiptHandle(message.receiptHandle()));
            }
            return of(id).size() >= count;
        });
        return of(id);
    }

    private List<JsonNode> of(String syncJobId) {
        return received.stream().filter(event -> syncJobId.equals(event.get("aggregateId").asString())).toList();
    }

    private Set<String> bodies(Jobs job) {
        return MOCK.requests().stream().map(FakeMock.Received::body).filter(body -> body.contains(job.key))
                .collect(Collectors.toSet());
    }

    private double held(String jobType) {
        return meters.counter(JobHandler.JOBS_METRIC, "jobType", jobType, "outcome", "held").count();
    }

    private void send(Jobs job) {
        String url = sqs.getQueueUrl(request -> request.queueName(job.queue())).queueUrl();
        sqs.sendMessage(request -> request.queueUrl(url).messageBody(job.body())
                .messageAttributes(job.attributes(Map.of())));
    }
}
