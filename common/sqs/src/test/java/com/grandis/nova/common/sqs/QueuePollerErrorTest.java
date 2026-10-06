package com.grandis.nova.common.sqs;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityResponse;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlResponse;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;
import software.amazon.awssdk.services.sqs.model.SqsException;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 작업 스레드가 조용히 끝나지 않는가. 처리 중 Error 는 그 한 건의 실패로 끝나야 하고,
 * 작업이 정말 끝났다면 isRunning() 이 그것을 드러내야 한다. 큐 동작은 대역 클라이언트로 흉내 낸다.
 */
class QueuePollerErrorTest {

    static final QueuePollerSettings SETTINGS = new QueuePollerSettings("order-events", 1, 0, 10,
            Duration.ofMinutes(5), Duration.ofSeconds(1), Duration.ofSeconds(1));

    final SqsClient sqs = mock(SqsClient.class);
    final AtomicInteger receives = new AtomicInteger();
    RetryingQueueConsumer consumer;

    @AfterEach
    void tearDown() {
        if (consumer != null) {
            consumer.stop();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void 처리_중_Error_가_나도_그_메시지만_실패로_두고_계속_받는다() {
        queueReturnsOnce(message("poison"));
        consumer = new RetryingQueueConsumer(sqs, queueUrls(), SETTINGS, message -> {
            throw new AssertionError("깨진 메시지");
        });

        consumer.start();

        await().atMost(Duration.ofSeconds(5)).until(() -> receives.get() >= 3);
        verify(sqs).changeMessageVisibility(any(Consumer.class));
        verify(sqs, never()).deleteMessage(any(Consumer.class));
        assertThat(consumer.isRunning()).isTrue();
    }

    /** 스택 넘침은 스택이 풀리면 회복된다. 특정 본문에서만 끝없이 재귀하는 처리 버그로 큐 전체가 멈추면 안 된다. */
    @Test
    @SuppressWarnings("unchecked")
    void 처리_중_스택이_넘쳐도_그_메시지만_실패로_두고_계속_받는다() {
        queueReturnsOnce(message("deep"));
        consumer = new RetryingQueueConsumer(sqs, queueUrls(), SETTINGS, message -> {
            throw new StackOverflowError("흉내");
        });

        consumer.start();

        await().atMost(Duration.ofSeconds(5)).until(() -> receives.get() >= 3);
        verify(sqs).changeMessageVisibility(any(Consumer.class));
        assertThat(consumer.isRunning()).isTrue();
    }

    @Test
    void 메모리가_부족해_작업이_끝나면_돌고_있다고_하지_않는다() {
        queueReturnsOnce(message("oom"));
        consumer = new RetryingQueueConsumer(sqs, queueUrls(), SETTINGS, message -> {
            throw new OutOfMemoryError("흉내");
        });

        consumer.start();

        await().atMost(Duration.ofSeconds(5)).until(() -> !consumer.isRunning());
    }

    @Test
    @SuppressWarnings("unchecked")
    void 받기가_실패하면_쉬었다_다시_받는다() {
        stubQueueUrl();
        willAnswer(invocation -> {
            if (receives.incrementAndGet() <= 2) {
                throw SqsException.builder().message("queue down").build();
            }
            return ReceiveMessageResponse.builder().build();
        }).given(sqs).receiveMessage(any(Consumer.class));
        consumer = new RetryingQueueConsumer(sqs, queueUrls(), SETTINGS, message -> {
        });

        consumer.start();

        await().atMost(Duration.ofSeconds(10)).until(() -> receives.get() >= 3);
        verify(sqs, atLeast(3)).receiveMessage(any(Consumer.class));
        assertThat(consumer.isRunning()).isTrue();
    }

    @SuppressWarnings("unchecked")
    private void queueReturnsOnce(Message message) {
        stubQueueUrl();
        willAnswer(invocation -> receives.incrementAndGet() == 1
                ? ReceiveMessageResponse.builder().messages(message).build()
                : ReceiveMessageResponse.builder().build()).given(sqs).receiveMessage(any(Consumer.class));
        given(sqs.changeMessageVisibility(any(Consumer.class)))
                .willReturn(ChangeMessageVisibilityResponse.builder().build());
    }

    @SuppressWarnings("unchecked")
    private void stubQueueUrl() {
        given(sqs.getQueueUrl(any(Consumer.class))).willAnswer(invocation -> {
            Consumer<GetQueueUrlRequest.Builder> request = invocation.getArgument(0);
            GetQueueUrlRequest.Builder builder = GetQueueUrlRequest.builder();
            request.accept(builder);
            return GetQueueUrlResponse.builder().queueUrl("http://sqs/" + builder.build().queueName()).build();
        });
    }

    private SqsQueueUrls queueUrls() {
        return new SqsQueueUrls(sqs, new SqsClientProperties(null, null, "test", "test", Map.of(), Duration.ofSeconds(3)));
    }

    private static Message message(String body) {
        return Message.builder().messageId(body).receiptHandle("rh-" + body).body(body)
                .attributesWithStrings(Map.of("ApproximateReceiveCount", "1")).build();
    }
}
