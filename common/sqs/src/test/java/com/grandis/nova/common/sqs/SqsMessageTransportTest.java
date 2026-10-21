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
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /** 묶음은 SendMessageBatch 한 번으로 — 건마다 본문 · 종류 · id 속성이 그대로 닿는다. */
    @Test
    void 묶음을_한_번에_보내고_건마다_본문과_속성이_닿는다() {
        runner.run(ctx -> {
            List<OutboundMessage> messages = messages(3);

            Map<Integer, RuntimeException> failures = ctx.getBean(MessageTransport.class).sendAll(messages);

            assertThat(failures).isEmpty();
            Map<String, Message> received = receiveAll(ctx.getBean(SqsClient.class), messages);
            assertThat(messages).allSatisfy(message -> {
                Message got = received.get(message.body());
                assertThat(got.messageAttributes().get("eventId").stringValue()).isEqualTo(message.eventId());
                assertThat(got.messageAttributes().get("eventType").stringValue()).isEqualTo("ITEM_SETTLED");
            });
        });
    }

    /** 합계 크기 한도를 넘으면 나눠 부른다 — 모두 닿는다. */
    @Test
    void 크기_한도를_넘는_묶음은_나눠_보낸다() {
        runner.run(ctx -> {
            SqsClient sqs = ctx.getBean(SqsClient.class);
            MessageTransport small = new SqsMessageTransport(sqs, ctx.getBean(SqsQueueUrls.class), Duration.ofSeconds(3),
                    200);
            List<OutboundMessage> messages = messages(4);

            assertThat(small.sendAll(messages)).isEmpty();

            assertThat(receiveAll(sqs, messages)).hasSize(messages.size());
        });
    }

    /** 나눠 부르다 전송 시간이 다하면 남은 건은 부르지 않고 실패로 돌린다 — 리스가 끝난 뒤 보내지 않게. */
    @Test
    void 나눠_부르다_시간이_다하면_남은_건은_실패로_돌린다() {
        runner.run(ctx -> {
            SqsClient sqs = ctx.getBean(SqsClient.class);
            MessageTransport hurried = new SqsMessageTransport(sqs, ctx.getBean(SqsQueueUrls.class), Duration.ofNanos(1),
                    200);
            List<OutboundMessage> messages = messages(3);

            Map<Integer, RuntimeException> failures = hurried.sendAll(messages);

            assertThat(failures).as("첫 호출은 부른다").containsOnlyKeys(1, 2);
            receiveAll(sqs, messages.subList(0, 1));
        });
    }

    /** 속성이 잘못된 건은 그 건만 실패다 — 나머지는 보낸다. */
    @Test
    void 속성이_잘못된_건만_실패로_돌려준다() {
        runner.run(ctx -> {
            String good = UUID.randomUUID().toString();
            List<OutboundMessage> messages = List.of(
                    new OutboundMessage("test-events", "bad", "ITEM_SETTLED", "{}", Map.of("eventType", "OTHER")),
                    new OutboundMessage("test-events", good, "ITEM_SETTLED", good));

            Map<Integer, RuntimeException> failures = ctx.getBean(MessageTransport.class).sendAll(messages);

            assertThat(failures).containsOnlyKeys(0);
            assertThat(new TestQueues(ctx.getBean(SqsClient.class))
                    .receive("test-events", m -> m.body().equals(good), Duration.ofSeconds(10))).isPresent();
        });
    }

    /**
     * 보낸 메시지를 모두 받는다(본문 → 메시지). 한 번에 여럿이 오므로 맞는 것을 다 지운다 — 하나씩 받으면 나머지가 보이지 않는 동안
     * 기다린다. 다른 시험의 메시지는 그대로 둔다.
     */
    private static Map<String, Message> receiveAll(SqsClient sqs, List<OutboundMessage> messages) {
        Set<String> bodies = messages.stream().map(OutboundMessage::body).collect(Collectors.toSet());
        String url = sqs.getQueueUrl(request -> request.queueName("test-events")).queueUrl();
        Map<String, Message> received = new HashMap<>();
        Instant deadline = Instant.now().plusSeconds(10);
        while (received.size() < bodies.size() && Instant.now().isBefore(deadline)) {
            for (Message message : sqs.receiveMessage(request -> request.queueUrl(url).waitTimeSeconds(1)
                    .maxNumberOfMessages(10).visibilityTimeout(1).messageAttributeNames("All")).messages()) {
                if (bodies.contains(message.body())) {
                    sqs.deleteMessage(request -> request.queueUrl(url).receiptHandle(message.receiptHandle()));
                    received.put(message.body(), message);
                }
            }
        }
        assertThat(received.keySet()).as("보낸 메시지가 모두 닿았다").isEqualTo(bodies);
        return received;
    }

    private static List<OutboundMessage> messages(int count) {
        return IntStream.range(0, count).mapToObj(i -> {
            String eventId = UUID.randomUUID().toString();
            return new OutboundMessage("test-events", eventId, "ITEM_SETTLED", "{\"eventId\":\"" + eventId + "\"}");
        }).toList();
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
