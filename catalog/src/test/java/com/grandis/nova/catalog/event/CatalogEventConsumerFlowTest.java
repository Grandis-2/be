package com.grandis.nova.catalog.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.grandis.nova.catalog.support.CatalogIntegrationTest;
import com.grandis.nova.common.message.EventEnvelope;
import com.grandis.nova.common.sqs.testing.FlociTestContainer;
import com.grandis.nova.common.sqs.testing.SqsTestConfig;
import com.grandis.nova.common.sqs.testing.TestQueues;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.json.JsonMapper;

/**
 * 실제 SQS 프로토콜(Floci) 위에서 catalog-events → 소비기 → 분배기 → 처리기. 처리한 메시지는 지우고, 처리하지 못한 메시지는
 * 지우지 않아 재수신 한도(Floci 큐는 2회) 뒤 DLQ 로 간다. 다시 보이기까지의 대기를 1초로 줄여 시험 시간 안에 한도에 닿게 한다.
 */
@CatalogIntegrationTest
@TestPropertySource(properties = {
        "nova.sqs.region=" + FlociTestContainer.REGION,
        "nova.sqs.consumer.enabled=true",
        "nova.sqs.consumer.wait-seconds=1",
        "nova.sqs.consumer.visibility=2s",
        "nova.sqs.consumer.backoff-base=1s",
        "nova.sqs.consumer.backoff-max=1s"
})
@Import({SqsTestConfig.class, CatalogEventConsumerFlowTest.Handlers.class})
class CatalogEventConsumerFlowTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String QUEUE = "catalog-events";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Autowired TestQueues queues;
    @Autowired RecordingHandler handler;

    @Test
    @DisplayName("처리기가 맡는 이벤트는 처리기에 닿고 큐에서 지워진다 — 대기 중 · 받은 채 남은 메시지가 0 이 된다")
    void handledMessageIsDeleted() throws InterruptedException {
        UUID productId = UUID.randomUUID();

        queues.send(QUEUE, body(RecordingHandler.EVENT_TYPE, RecordingHandler.AGGREGATE_TYPE, productId));

        await(() -> handler.ids.contains(productId), "처리기에 상품 " + productId + " 의 이벤트가 닿지 않았다");
        await(() -> queues.messagesIn(QUEUE) == 0, "처리한 메시지가 큐에서 지워지지 않았다");
        assertThat(handler.ids.stream().filter(productId::equals)).as("한 번만 처리").hasSize(1);
        assertThat(queues.receive(QUEUE + "-dlq", message -> message.body().contains(productId.toString()), Duration.ofSeconds(1)))
                .as("큐에서 빠진 까닭이 DLQ 이동이 아니라 지우기").isEmpty();
    }

    @Test
    @DisplayName("받지 않는 이벤트 종류는 지우지 않아 재수신 한도 뒤 DLQ 로 간다 — 처리기는 부르지 않는다")
    void unknownEventGoesToDeadLetterQueue() {
        UUID productId = UUID.randomUUID();

        queues.send(QUEUE, body("NOT_A_CATALOG_EVENT", RecordingHandler.AGGREGATE_TYPE, productId));

        assertThat(queues.receive(QUEUE + "-dlq", message -> message.body().contains(productId.toString()), TIMEOUT))
                .as("DLQ 로 옮겨짐").isPresent();
        assertThat(handler.ids).doesNotContain(productId);
    }

    private static void await(BooleanSupplier condition, String failure) throws InterruptedException {
        Instant deadline = Instant.now().plus(TIMEOUT);
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError(failure);
            }
            Thread.sleep(100);
        }
    }

    private static String body(String eventType, String aggregateType, UUID aggregateId) {
        return JSON.writeValueAsString(new EventEnvelope(UUID.randomUUID().toString(), eventType, aggregateType, aggregateId,
                Instant.now(), JSON.readTree("{}")));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Handlers {

        @Bean
        RecordingHandler recordingHandler() {
            return new RecordingHandler();
        }
    }

    static final class RecordingHandler implements CatalogEventHandler {

        static final String EVENT_TYPE = "TEST_CATALOG_EVENT";
        static final String AGGREGATE_TYPE = "PRODUCT";

        final List<UUID> ids = new CopyOnWriteArrayList<>();

        @Override
        public String eventType() {
            return EVENT_TYPE;
        }

        @Override
        public String aggregateType() {
            return AGGREGATE_TYPE;
        }

        @Override
        public void handle(UUID aggregateId, EventEnvelope envelope) {
            ids.add(aggregateId);
        }
    }
}
