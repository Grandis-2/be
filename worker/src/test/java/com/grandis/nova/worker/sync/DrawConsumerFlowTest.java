package com.grandis.nova.worker.sync;

import com.grandis.nova.common.sqs.testing.FlociTestContainer;
import com.grandis.nova.common.sqs.testing.SqsTestConfig;
import com.grandis.nova.common.sqs.testing.TestQueues;
import com.grandis.nova.worker.mock.FakeMock;
import com.sun.net.httpserver.HttpExchange;
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
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static com.grandis.nova.worker.mock.FakeMock.html;
import static com.grandis.nova.worker.mock.FakeMock.json;
import static com.grandis.nova.worker.sync.Draws.drawn;
import static com.grandis.nova.worker.sync.Draws.status;
import static com.grandis.nova.worker.sync.Draws.winners;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * draw-register 에 넣은 메시지가 소비기 → Mock → (늦춰 다시 받기) → 추첨 결과 발행 · DLQ 로 끝까지 이어지는지 본다(Floci · 가짜 mock).
 * 지우지 않고 늦춘 메시지는 큐의 최대 수신 횟수(시험 큐는 {@link FlociTestContainer#MAX_RECEIVE_COUNT})를 넘으면 SQS 가 DLQ 로 옮긴다.
 */
@SpringBootTest(properties = {
        "nova.sqs.region=ap-northeast-2",
        "nova.worker.consumer.wait-seconds=1",
        "nova.worker.consumer.visibility=3s",
        "nova.worker.mock.connect-timeout=300ms",
        "nova.worker.mock.read-timeout=300ms",
        "nova.worker.mock.slow-call-threshold=150ms",
        "nova.worker.retry.in-round-delay-min=10ms",
        "nova.worker.retry.in-round-delay-max=20ms",
        "nova.worker.draw.retry-delays=1s",
        // 시험 큐는 2번 받으면 DLQ 로 옮긴다. 두 번째로 받기 전에 늦은 응모가 닿을 만큼 기다린다
        "nova.worker.draw.wait-delays=6s",
        "nova.worker.circuit.window=100",
        "nova.worker.circuit.minimum-calls=100"})
@Import(SqsTestConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DrawConsumerFlowTest {

    static final FakeMock MOCK = new FakeMock();
    static final String ENTERED = "{\"entryId\":\"e\",\"drawId\":\"d\",\"customerId\":\"c\",\"receivedAt\":\"2026-10-14T00:00:00Z\"}";

    @Autowired
    SqsClient sqs;

    @Autowired
    TestQueues queues;

    final Draws draw = new Draws();

    @DynamicPropertySource
    static void mock(DynamicPropertyRegistry registry) {
        registry.add("nova.worker.mock.base-url", () -> MOCK.baseUrl().toString());
    }

    @AfterAll
    static void closeMock() {
        MOCK.close();
    }

    /** 늦은 응모가 닿을 때까지 기다렸다가, 수가 맞으면 추첨해 결과를 보내고 원본을 지운다. */
    @Test
    void 늦은_응모가_닿으면_추첨해_결과를_order_에_보낸다() {
        AtomicInteger received = new AtomicInteger(1);
        List<String> winners = winners(1);
        MOCK.respond((HttpExchange exchange) -> {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/external/draw-entries")) {
                received.incrementAndGet();
                json(201, ENTERED).handle(exchange);
            } else if ("GET".equals(exchange.getRequestMethod())) {
                status(received.get()).handle(exchange);
            } else {
                drawn(winners).handle(exchange);
            }
        });

        send(draw.requestedBody(2, 1));
        await().atMost(Duration.ofSeconds(10))
                .until(() -> draw.calls(MOCK).contains("GET /external/draws/{drawId}"));
        assertThat(draw.calls(MOCK)).as("1건뿐이라 추첨하지 않고 기다린다").doesNotContain("POST /external/draws/{drawId}/draw");

        send(draw.entryBody());

        Message result = draw.result(queues, Duration.ofSeconds(15))
                .orElseThrow(() -> new AssertionError("추첨 결과가 오지 않았다"));
        JsonNode payload = Draws.body(result).get("payload");
        assertThat(payload.get("winnerEntryIds").valueStream().map(JsonNode::asString).toList()).isEqualTo(winners);
        assertThat(draw.calls(MOCK)).containsOnlyOnce("POST /external/draws/{drawId}/draw");
        await().during(Duration.ofSeconds(5)).atMost(Duration.ofSeconds(8))
                .until(() -> draw.calls(MOCK).stream().filter(call -> call.endsWith("/draw")).count() == 1);
    }

    @Test
    void 일시_실패가_이어지면_최대_수신_횟수를_넘긴_뒤_SQS_가_DLQ_로_옮긴다() {
        MOCK.respond(html(503));

        send(draw.entryBody());

        Message dead = draw.deadLetter(queues, Duration.ofSeconds(30))
                .orElseThrow(() -> new AssertionError("DLQ 로 가지 않았다"));
        assertThat(Draws.attributes(dead)).doesNotContainKey("deadLetterReason");
        assertThat(draw.calls(MOCK)).as("받을 때마다 한 번 + 곧바로 한 번 더")
                .hasSize(FlociTestContainer.MAX_RECEIVE_COUNT * 2);
    }

    private void send(String body) {
        String url = sqs.getQueueUrl(request -> request.queueName(Draws.QUEUE)).queueUrl();
        String eventType = Draws.body(Message.builder().body(body).build()).get("eventType").asString();
        sqs.sendMessage(request -> request.queueUrl(url).messageBody(body).messageAttributes(Map.of(
                "eventType", MessageAttributeValue.builder().dataType("String").stringValue(eventType).build())));
    }
}
