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
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    /** 추가 속성이 없으면(기존 서비스) 종류 · id 둘만 나간다. */
    @Test
    void 메시지가_준_추가_속성을_종류_id_와_함께_보낸다() {
        runner.run(ctx -> {
            String eventId = UUID.randomUUID().toString();

            ctx.getBean(MessageTransport.class).send(new OutboundMessage("test-events", eventId, "ITEM_SETTLED", eventId,
                    Map.of("attempt", "1", "actor", "ADMIN")));
            ctx.getBean(MessageTransport.class).send(new OutboundMessage("test-events", eventId, "ITEM_PLAIN", eventId));

            TestQueues queues = new TestQueues(ctx.getBean(SqsClient.class));
            Message withExtra = queues.receive("test-events", m -> m.body().equals(eventId)
                    && m.messageAttributes().get("eventType").stringValue().equals("ITEM_SETTLED"),
                    Duration.ofSeconds(10)).orElseThrow();
            assertThat(withExtra.messageAttributes().keySet())
                    .containsExactlyInAnyOrder("eventType", "eventId", "attempt", "actor");
            assertThat(withExtra.messageAttributes().get("attempt").stringValue()).isEqualTo("1");
            assertThat(withExtra.messageAttributes().get("actor").stringValue()).isEqualTo("ADMIN");
            Message plain = queues.receive("test-events", m -> m.body().equals(eventId)
                    && m.messageAttributes().get("eventType").stringValue().equals("ITEM_PLAIN"),
                    Duration.ofSeconds(10)).orElseThrow();
            assertThat(plain.messageAttributes().keySet()).containsExactlyInAnyOrder("eventType", "eventId");
        });
    }

    /** 종류 · id 를 덮어쓰면 받는 쪽이 엉뚱한 처리로 보낸다. SQS 는 속성을 10개까지만 받는다. */
    @Test
    void 종류_id_를_덮어쓰거나_한도를_넘는_추가_속성은_보내지_않고_거절한다() {
        runner.run(ctx -> {
            MessageTransport transport = ctx.getBean(MessageTransport.class);
            Map<String, String> nine = IntStream.rangeClosed(1, 9).boxed()
                    .collect(Collectors.toMap(i -> "k" + i, String::valueOf));

            assertThatThrownBy(() -> transport.send(new OutboundMessage("test-events", "e", "T", "{}",
                    Map.of("eventType", "OTHER")))).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> transport.send(new OutboundMessage("test-events", "e", "T", "{}",
                    Map.of("eventId", "other")))).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> transport.send(new OutboundMessage("test-events", "e", "T", "{}", nine)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("8개");
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
