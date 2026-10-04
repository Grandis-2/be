package com.grandis.nova.order.event;

import com.grandis.nova.common.sqs.SqsQueueUrls;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** 지연 재발행의 수치(에픽 U7: 60s → 최대 900s 두 배씩, 24h 뒤 경보 한 번 — 포기하지 않는다)와 이어 받는 속성. SQS 프로토콜은 SqsMessagingTest(Floci)가 본다. */
@ExtendWith(OutputCaptureExtension.class)
class DeferredRedeliveryTest {

    static final Instant NOW = Instant.parse("2026-10-04T03:00:00Z");
    static final String QUEUE_URL = "http://floci/000000000000/order-events";
    static final DeferredRedelivery.Settings DEFAULTS =
            new DeferredRedelivery.Settings(Duration.ofSeconds(60), Duration.ofMinutes(15), Duration.ofHours(24));

    SqsClient sqs;
    DeferredRedelivery redelivery;

    @BeforeEach
    void setUp() {
        sqs = mock(SqsClient.class);
        SqsQueueUrls queueUrls = mock(SqsQueueUrls.class);
        given(queueUrls.of("order-events")).willReturn(QUEUE_URL);
        redelivery = new DeferredRedelivery(sqs, queueUrls, "order-events", DEFAULTS, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void firstDeferralWaitsFirstDelayAndStartsTheClock() {
        SendMessageRequest sent = redeliver(message(Map.of("eventType", text("PREORDER_CANCEL_REQUESTED"))));

        assertThat(sent.queueUrl()).isEqualTo(QUEUE_URL);
        assertThat(sent.messageBody()).isEqualTo("body");
        assertThat(sent.delaySeconds()).isEqualTo(60);
        assertThat(sent.messageAttributes().get("deferCount").stringValue()).isEqualTo("1");
        assertThat(sent.messageAttributes().get("firstDeferredAt").stringValue()).isEqualTo(NOW.toString());
        assertThat(sent.messageAttributes().get("eventType").stringValue()).isEqualTo("PREORDER_CANCEL_REQUESTED");
    }

    @Test
    void delayDoublesUpToSqsMaximumAndKeepsFirstDeferralTime() {
        Instant first = NOW.minus(Duration.ofHours(3));
        int[] expected = {60, 120, 240, 480, 900, 900};
        for (int count = 0; count < expected.length; count++) {
            SendMessageRequest sent = redeliver(message(deferred(count, first)));

            assertThat(sent.delaySeconds()).as("deferCount=%d", count).isEqualTo(expected[count]);
            assertThat(sent.messageAttributes().get("deferCount").stringValue()).isEqualTo(String.valueOf(count + 1));
            assertThat(sent.messageAttributes().get("firstDeferredAt").stringValue()).isEqualTo(first.toString());
        }
    }

    // preorder 에 결과를 돌려줄 재료는 이 메시지뿐이라 버리지 않는다 — 경보 기준이 지나면 ERROR 를 한 번 남기고 계속 늦춘다
    @Test
    void pastAlertTimeKeepsRedeliveringAndAlertsOnce(CapturedOutput output) {
        SendMessageRequest sent = redeliver(message(deferred(95, NOW.minus(Duration.ofHours(24)))));

        assertThat(sent.delaySeconds()).isEqualTo(900);
        assertThat(sent.messageAttributes().get("deferAlerted").stringValue()).isEqualTo("true");
        assertThat(output.getAll()).containsOnlyOnce("예약 취소 보류가 길어진다 — 사람 확인 필요");
    }

    @Test
    void alreadyAlertedDeferralDoesNotAlertAgain(CapturedOutput output) {
        Map<String, MessageAttributeValue> attributes = new HashMap<>(deferred(96, NOW.minus(Duration.ofHours(25))));
        attributes.put("deferAlerted", text("true"));

        SendMessageRequest sent = redeliver(message(attributes));

        assertThat(sent.messageAttributes().get("deferAlerted").stringValue()).isEqualTo("true");
        assertThat(output.getAll()).doesNotContain("예약 취소 보류가 길어진다");
    }

    // 권한 · 큐 설정 오류가 첫 건에서 보이게 ERROR 로 올리고, 원본을 다시 받도록 예외를 던진다
    @Test
    void failedRedeliveryIsLoudAndKeepsOriginal(CapturedOutput output) {
        given(sqs.sendMessage(any(Consumer.class))).willThrow(new IllegalStateException("AccessDenied"));

        assertThatThrownBy(() -> redelivery.redeliver(message(Map.of()))).isInstanceOf(IllegalStateException.class);

        assertThat(output.getAll()).contains("늦춰 다시 보내지 못했다 — 큐 권한 · 설정 확인 필요");
    }

    // 속성이 깨졌으면 처음 보류한 것으로 본다 — 상한이 늦어질 뿐 끝은 난다
    @Test
    void unreadableAttributesStartOver() {
        SendMessageRequest sent = redeliver(message(Map.of("deferCount", text("many"),
                "firstDeferredAt", text("yesterday"))));

        assertThat(sent.delaySeconds()).isEqualTo(60);
        assertThat(sent.messageAttributes().get("firstDeferredAt").stringValue()).isEqualTo(NOW.toString());
    }

    @Test
    void settingsMustFitSqsDelayLimit() {
        assertThatThrownBy(() -> new DeferredRedelivery.Settings(Duration.ofSeconds(60), Duration.ofMinutes(16),
                Duration.ofHours(24))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DeferredRedelivery.Settings(Duration.ofMillis(500), Duration.ofMinutes(15),
                Duration.ofHours(24))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DeferredRedelivery.Settings(Duration.ofSeconds(60), Duration.ofMinutes(15),
                Duration.ofMinutes(10))).isInstanceOf(IllegalArgumentException.class);
    }

    @SuppressWarnings("unchecked")
    private SendMessageRequest redeliver(Message message) {
        redelivery.redeliver(message);
        ArgumentCaptor<Consumer<SendMessageRequest.Builder>> captor = ArgumentCaptor.forClass(Consumer.class);
        verify(sqs, atLeastOnce()).sendMessage(captor.capture());
        SendMessageRequest.Builder builder = SendMessageRequest.builder();
        captor.getValue().accept(builder);
        return builder.build();
    }

    private static Message message(Map<String, MessageAttributeValue> attributes) {
        return Message.builder().messageId("m-1").body("body").messageAttributes(new HashMap<>(attributes)).build();
    }

    private static Map<String, MessageAttributeValue> deferred(int count, Instant first) {
        return Map.of("deferCount", MessageAttributeValue.builder().dataType("Number").stringValue(String.valueOf(count))
                .build(), "firstDeferredAt", text(first.toString()));
    }

    private static MessageAttributeValue text(String value) {
        return MessageAttributeValue.builder().dataType("String").stringValue(value).build();
    }
}
