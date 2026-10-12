package com.grandis.nova.worker.sync;

import com.grandis.nova.common.sqs.testing.SqsTestConfig;
import com.grandis.nova.common.sqs.testing.TestQueues;
import com.grandis.nova.worker.mock.FakeMock;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import software.amazon.awssdk.services.sqs.SqsClient;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.Map;

import static com.grandis.nova.worker.mock.FakeMock.html;
import static com.grandis.nova.worker.mock.FakeMock.inOrder;
import static com.grandis.nova.worker.mock.FakeMock.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 큐에 넣은 등록 작업이 소비기 → 외부 호출 → 결과 이벤트 → 지연 재전송 → 다시 소비로 끝까지 이어지는지 본다(Floci · 가짜 mock).
 * 상태는 메시지 속성으로만 이어진다 — preorder 가 정한 실행 회차 · 시작한 쪽이 다음 차례의 결과에도 실려야 한다.
 */
@SpringBootTest(properties = {
        "nova.sqs.region=ap-northeast-2",
        "nova.worker.consumer.wait-seconds=1",
        // 지우지 못한 메시지가 곧 다시 보이게 해, 처리 뒤 삭제됐는지를 "다시 부르지 않음"으로 확인한다
        "nova.worker.consumer.visibility=3s",
        "nova.worker.mock.connect-timeout=300ms",
        "nova.worker.mock.read-timeout=300ms",
        "nova.worker.mock.slow-call-threshold=150ms",
        "nova.worker.retry.in-round-delay-min=10ms",
        "nova.worker.retry.in-round-delay-max=20ms",
        "nova.worker.retry.backoff=1s,1s,1s",
        "nova.worker.retry.jitter-ratio=0",
        "nova.worker.circuit.window=100",
        "nova.worker.circuit.minimum-calls=100"})
@Import(SqsTestConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RegisterConsumerFlowTest {

    static final FakeMock MOCK = new FakeMock();
    static final String CREATED = "{\"externalKey\":\"K\",\"externalNumber\":\"R-7\",\"status\":\"ACTIVE\"}";

    @Autowired
    SqsClient sqs;

    @Autowired
    TestQueues queues;

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
    void 실패한_차례를_알리고_지연_뒤_다시_받아_성공까지_이어진다() {
        MOCK.respond(inOrder(html(503), html(503), json(201, CREATED)));

        send(Map.of("run", "2", "attempt", "1", "generation", "1", "actor", "ADMIN"));

        JsonNode attempted = job.event(queues);
        assertThat(attempted.get("eventType").asString()).isEqualTo("EXTERNAL_JOB_ATTEMPTED");
        assertThat(attempted.get("payload").get("attempt").get("attempt").asInt()).isEqualTo(1);
        JsonNode succeeded = job.event(queues);
        assertThat(succeeded.get("eventType").asString()).isEqualTo("EXTERNAL_JOB_SUCCEEDED");
        assertThat(succeeded.get("payload").get("externalNumber").asString()).isEqualTo("R-7");
        JsonNode attempt = succeeded.get("payload").get("attempt");
        assertThat(attempt.get("attempt").asInt()).as("다음 차례에서 성공했다").isEqualTo(2);
        assertThat(attempt.get("run").asInt()).as("실행 회차를 그대로 잇는다").isEqualTo(2);
        assertThat(attempt.get("actor").asString()).isEqualTo("ADMIN");
        assertThat(job.calls(MOCK)).hasSize(3);
    }

    /** 속성 없이 온 메시지(preorder 의 첫 발행이 속성을 싣기 전 모양)도 처음으로 보고 처리한다. */
    @Test
    void 성공한_메시지는_큐에서_지워_다시_부르지_않는다() {
        MOCK.respond(json(201, CREATED));

        send(Map.of());

        assertThat(job.event(queues).get("eventType").asString()).isEqualTo("EXTERNAL_JOB_SUCCEEDED");
        await().during(Duration.ofSeconds(5)).atMost(Duration.ofSeconds(8)).until(() -> job.calls(MOCK).size() == 1);
    }

    private void send(Map<String, String> attributes) {
        String url = sqs.getQueueUrl(request -> request.queueName(Jobs.REGISTER_QUEUE)).queueUrl();
        sqs.sendMessage(request -> request.queueUrl(url).messageBody(job.body())
                .messageAttributes(Jobs.attributes(attributes)));
    }
}
