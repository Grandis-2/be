package com.grandis.nova.common.sqs;

import com.grandis.nova.common.outbox.MessageTransport;
import com.grandis.nova.common.outbox.OutboundMessage;
import com.grandis.nova.common.sqs.testing.TestQueues;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 실제 SQS 프로토콜(Floci)로 아웃박스 메시지가 논리 목적지의 큐에 본문 · 속성 그대로 닿는지 본다. */
class SqsMessageTransportTest {

    final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SqsAutoConfiguration.class))
            .withPropertyValues(FlociSqs.propertyValues())
            .withPropertyValues("nova.outbox.transport=sqs");

    @Test
    void 논리_목적지의_큐로_본문과_종류_id_속성을_보낸다() {
        runner.run(ctx -> {
            String eventId = UUID.randomUUID().toString();
            String body = "{\"eventId\":\"" + eventId + "\"}";

            ctx.getBean(MessageTransport.class).send(new OutboundMessage("test-events", eventId, "ITEM_SETTLED", body));

            Message received = new TestQueues(ctx.getBean(SqsClient.class))
                    .receive("test-events", m -> m.body().equals(body), Duration.ofSeconds(10)).orElseThrow();
            assertThat(received.messageAttributes().get("eventType").stringValue()).isEqualTo("ITEM_SETTLED");
            assertThat(received.messageAttributes().get("eventId").stringValue()).isEqualTo(eventId);
        });
    }

    /** 환경마다 큐 이름에 접두어를 붙일 때 — 논리 이름은 코드에, 실제 이름은 설정에 있다. */
    @Test
    void 설정한_실제_큐_이름으로_보낸다() {
        runner.withPropertyValues("nova.sqs.queues.logical-events=test-events").run(ctx -> {
            String eventId = UUID.randomUUID().toString();

            ctx.getBean(MessageTransport.class).send(new OutboundMessage("logical-events", eventId, "ITEM_SETTLED", eventId));

            assertThat(new TestQueues(ctx.getBean(SqsClient.class))
                    .receive("test-events", m -> m.body().equals(eventId), Duration.ofSeconds(10))).isPresent();
        });
    }
}
