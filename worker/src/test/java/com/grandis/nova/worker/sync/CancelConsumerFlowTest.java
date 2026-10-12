package com.grandis.nova.worker.sync;

import com.grandis.nova.common.sqs.testing.SqsTestConfig;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.grandis.nova.worker.mock.FakeMock.after;
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
        "nova.worker.mock.connect-timeout=4s",
        "nova.worker.mock.read-timeout=4s",
        "nova.worker.mock.slow-call-threshold=3500ms",
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
    MeterRegistry meters;

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
        MOCK.respond(exchange -> (exchange.getRequestURI().getPath().endsWith("/cancellations")
                ? after(Duration.ofMillis(500), CANCELED) : after(Duration.ofMillis(2500), CREATED)).handle(exchange));
        Jobs register = new Jobs();
        List<Jobs> cancels = List.of(Jobs.cancel(), Jobs.cancel(), Jobs.cancel());

        send(register);
        await().atMost(Duration.ofSeconds(10)).until(() -> !register.calls(MOCK).isEmpty());
        // 등록을 받기 전에 건수를 정하고 기다리던 수신(최대 wait-seconds)이 끝난 뒤에 넣는다
        await().pollDelay(Duration.ofMillis(1500)).until(() -> true);
        cancels.forEach(this::send);

        Map<String, JsonNode> events = events(4);
        for (Jobs cancel : cancels) {
            JsonNode event = events.get(cancel.syncJobId.toString());
            assertThat(event.get("eventType").asString()).isEqualTo("EXTERNAL_JOB_SUCCEEDED");
            assertThat(event.get("payload").get("jobType").asString()).isEqualTo("CANCEL");
            assertThat(cancel.calls(MOCK)).as("한 번만 불렀다").hasSize(1);
        }
        assertThat(events.get(register.syncJobId.toString()).get("eventType").asString())
                .isEqualTo("EXTERNAL_JOB_SUCCEEDED");
        assertThat(held("CANCEL") + held("REGISTER")).as("자리를 못 얻어 돌려보낸 메시지가 없다").isZero();
    }

    /** 결과 이벤트를 작업 id 별로 모은다. 여러 건이 섞여 오므로 받은 것은 모두 지운다 — 남기면 받은 횟수가 차 DLQ 로 간다. */
    private Map<String, JsonNode> events(int count) {
        String url = sqs.getQueueUrl(request -> request.queueName(Jobs.EVENTS_QUEUE)).queueUrl();
        Map<String, JsonNode> events = new HashMap<>();
        await().atMost(Duration.ofSeconds(20)).until(() -> {
            for (Message message : sqs.receiveMessage(request -> request.queueUrl(url).waitTimeSeconds(1)
                    .maxNumberOfMessages(10)).messages()) {
                JsonNode event = JSON.readTree(message.body());
                events.put(event.get("aggregateId").asString(), event);
                sqs.deleteMessage(request -> request.queueUrl(url).receiptHandle(message.receiptHandle()));
            }
            return events.size() >= count;
        });
        return events;
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
