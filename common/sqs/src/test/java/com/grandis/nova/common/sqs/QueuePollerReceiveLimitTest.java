package com.grandis.nova.common.sqs;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlResponse;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

import java.time.Duration;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.mock;

/** 받을 개수를 따로 정하지 않는 소비기는 설정의 max-messages 를 그대로 받는다. */
class QueuePollerReceiveLimitTest {

    static final QueuePollerSettings SETTINGS = new QueuePollerSettings("order-events", 1, 0, 7,
            Duration.ofMinutes(5), Duration.ofSeconds(1), Duration.ofSeconds(1));

    final SqsClient sqs = mock(SqsClient.class);
    final Queue<Integer> requestedCounts = new ConcurrentLinkedQueue<>();
    RetryingQueueConsumer consumer;

    @AfterEach
    void tearDown() {
        consumer.stop();
    }

    @Test
    @SuppressWarnings("unchecked")
    void 설정한_개수만큼_쉬지_않고_받는다() {
        given(sqs.getQueueUrl(any(Consumer.class)))
                .willReturn(GetQueueUrlResponse.builder().queueUrl("http://sqs/order-events").build());
        willAnswer(invocation -> {
            Consumer<ReceiveMessageRequest.Builder> request = invocation.getArgument(0);
            ReceiveMessageRequest.Builder builder = ReceiveMessageRequest.builder();
            request.accept(builder);
            requestedCounts.add(builder.build().maxNumberOfMessages());
            Thread.sleep(20);
            return ReceiveMessageResponse.builder().build();
        }).given(sqs).receiveMessage(any(Consumer.class));
        consumer = new RetryingQueueConsumer(sqs, new SqsQueueUrls(sqs,
                new SqsClientProperties(null, null, "test", "test", Map.of(), Duration.ofSeconds(3))), SETTINGS,
                message -> {
                });

        consumer.start();

        // 받기 사이에 쉬었다면(0.1초) 1초에 10번을 넘지 못한다
        await().atMost(Duration.ofSeconds(1)).until(() -> requestedCounts.size() >= 20);
        assertThat(requestedCounts).containsOnly(7);
    }
}
