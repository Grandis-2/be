package com.grandis.nova.common.sqs;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SqsException;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** 지연 재전송이 SQS 에 넘기는 값. SQS 프로토콜은 BatchQueueConsumerTest(Floci)가 본다. */
class DelayedResendTest {

    static final String QUEUE_URL = "http://floci/000000000000/preorder-register";

    SqsClient sqs;
    DelayedResend resend;

    @BeforeEach
    void setUp() {
        sqs = mock(SqsClient.class);
        SqsQueueUrls queueUrls = mock(SqsQueueUrls.class);
        given(queueUrls.of("preorder-register")).willReturn(QUEUE_URL);
        resend = new DelayedResend(sqs, queueUrls, "preorder-register");
    }

    @Test
    void 본문은_그대로_같은_큐에_지연을_두고_보낸다() {
        SendMessageRequest sent = resend(Map.of(), Duration.ofSeconds(40));

        assertThat(sent.queueUrl()).isEqualTo(QUEUE_URL);
        assertThat(sent.messageBody()).isEqualTo("body");
        assertThat(sent.delaySeconds()).isEqualTo(40);
    }

    @Test
    void 받은_속성을_잇고_준_속성은_덮어쓰며_null_이면_뺀다() {
        Map<String, String> changes = new HashMap<>();
        changes.put("attempt", "3");
        changes.put("generation", "1");
        changes.put("lastOutcome", null);

        SendMessageRequest sent = resend(changes, Duration.ofSeconds(20));

        assertThat(sent.messageAttributes()).containsOnlyKeys("attempt", "generation", "eventType");
        assertThat(sent.messageAttributes().get("attempt").stringValue()).isEqualTo("3");
        assertThat(sent.messageAttributes().get("attempt").dataType()).isEqualTo("String");
        assertThat(sent.messageAttributes().get("generation").stringValue()).isEqualTo("1");
        assertThat(sent.messageAttributes().get("eventType").stringValue()).isEqualTo("REGISTER_JOB_READY");
    }

    @Test
    void 지연은_초로_올리고_0초와_15분까지_받는다() {
        assertThat(resend(Map.of(), Duration.ofMillis(100)).delaySeconds()).isEqualTo(1);
        assertThat(resend(Map.of(), Duration.ofNanos(1)).delaySeconds()).isEqualTo(1);
        assertThat(resend(Map.of(), Duration.ofMillis(1001)).delaySeconds()).isEqualTo(2);
        assertThat(resend(Map.of(), Duration.ZERO).delaySeconds()).isZero();
        assertThat(resend(Map.of(), Duration.ofMinutes(15)).delaySeconds()).isEqualTo(900);
    }

    @Test
    @SuppressWarnings("unchecked")
    void 범위_밖의_지연은_보내지_않고_거부한다() {
        assertThatThrownBy(() -> resend.resend(message(), Map.of(), Duration.ofMinutes(15).plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> resend.resend(message(), Map.of(), Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> resend.resend(message(), Map.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
        verify(sqs, never()).sendMessage(any(Consumer.class));
    }

    /** 던져야 호출자가 원본을 지우지 않는다. */
    @Test
    @SuppressWarnings("unchecked")
    void 보내지_못하면_던진다() {
        given(sqs.sendMessage(any(Consumer.class))).willThrow(SqsException.builder().message("denied").build());

        assertThatThrownBy(() -> resend.resend(message(), Map.of("attempt", "2"), Duration.ofSeconds(10)))
                .isInstanceOf(SqsException.class);
    }

    @SuppressWarnings("unchecked")
    private SendMessageRequest resend(Map<String, String> changes, Duration delay) {
        clearInvocations(sqs);
        resend.resend(message(), changes, delay);
        ArgumentCaptor<Consumer<SendMessageRequest.Builder>> captor = ArgumentCaptor.forClass(Consumer.class);
        verify(sqs).sendMessage(captor.capture());
        SendMessageRequest.Builder builder = SendMessageRequest.builder();
        captor.getValue().accept(builder);
        return builder.build();
    }

    private static Message message() {
        return Message.builder().messageId("m-1").body("body").messageAttributes(Map.of(
                "attempt", text("2"), "lastOutcome", text("UNKNOWN"), "eventType", text("REGISTER_JOB_READY"))).build();
    }

    private static MessageAttributeValue text(String value) {
        return MessageAttributeValue.builder().dataType("String").stringValue(value).build();
    }
}
