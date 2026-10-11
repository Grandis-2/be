package com.grandis.nova.common.sqs;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.BatchResultErrorEntry;
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityRequest;
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityResponse;
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchRequestEntry;
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchResponse;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlResponse;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;
import software.amazon.awssdk.services.sqs.model.SqsException;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** 받기 · 삭제 · 처리가 실패해도 소비가 이어지는가, SQS 에 넘기는 값이 맞는가. 큐 동작은 대역 클라이언트로 흉내 낸다. */
@ExtendWith(OutputCaptureExtension.class)
class BatchQueueConsumerFailureTest {

    static final BatchConsumerSettings SETTINGS =
            new BatchConsumerSettings("preorder-register", 0, Duration.ofMinutes(2), Duration.ofSeconds(5));
    static final Duration TIMEOUT = Duration.ofSeconds(10);

    final SqsClient sqs = mock(SqsClient.class);
    final AtomicInteger receives = new AtomicInteger();
    final Queue<Integer> requestedCounts = new ConcurrentLinkedQueue<>();
    final Queue<List<DeleteMessageBatchRequestEntry>> deleted = new ConcurrentLinkedQueue<>();
    final Queue<Integer> visibilities = new ConcurrentLinkedQueue<>();
    BatchQueueConsumer consumer;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        given(sqs.getQueueUrl(any(Consumer.class)))
                .willReturn(GetQueueUrlResponse.builder().queueUrl("http://sqs/preorder-register").build());
        willAnswer(invocation -> {
            Consumer<ChangeMessageVisibilityRequest.Builder> request = invocation.getArgument(0);
            ChangeMessageVisibilityRequest.Builder builder = ChangeMessageVisibilityRequest.builder();
            request.accept(builder);
            visibilities.add(builder.build().visibilityTimeout());
            return ChangeMessageVisibilityResponse.builder().build();
        }).given(sqs).changeMessageVisibility(any(Consumer.class));
    }

    @AfterEach
    void tearDown() {
        if (consumer != null) {
            consumer.stop();
        }
    }

    @Test
    void 받을_개수는_빈_자리_수이고_10을_넘지_않는다() {
        AtomicInteger slots = new AtomicInteger(3);
        queueReturnsOnce();
        consumer = consumer(slots::get, message -> MessageOutcome.delete());

        consumer.start();

        await().atMost(TIMEOUT).until(() -> requestedCounts.contains(3));
        slots.set(25);
        await().atMost(TIMEOUT).until(() -> requestedCounts.contains(10));
        assertThat(requestedCounts).containsOnly(3, 10);
    }

    @Test
    @SuppressWarnings("unchecked")
    void 묶음으로_지우고_일부를_지우지_못해도_계속_받는다(CapturedOutput output) {
        queueReturnsOnce(message("a"), message("b"));
        deleteBatchAnswers(DeleteMessageBatchResponse.builder().failed(BatchResultErrorEntry.builder()
                .id("1").code("ReceiptHandleIsInvalid").message("expired").build()).build());
        consumer = consumer(() -> 10, message -> MessageOutcome.delete());

        consumer.start();

        await().atMost(TIMEOUT).until(() -> !deleted.isEmpty());
        await().atMost(TIMEOUT).until(() -> receives.get() >= 3);
        assertThat(deleted).hasSize(1);
        assertThat(deleted.peek()).extracting(DeleteMessageBatchRequestEntry::receiptHandle)
                .containsExactlyInAnyOrder("rh-a", "rh-b");
        String failedBody = deleted.peek().get(1).receiptHandle().substring(3);
        assertThat(output.getOut()).contains("messageId=" + failedBody + " code=ReceiptHandleIsInvalid");
        verify(sqs, never()).deleteMessage(any(Consumer.class));
        assertThat(consumer.isRunning()).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void 묶음_삭제가_실패해도_계속_받는다() {
        queueReturnsOnce(message("a"));
        given(sqs.deleteMessageBatch(any(Consumer.class))).willThrow(SqsException.builder().message("down").build());
        consumer = consumer(() -> 10, message -> MessageOutcome.delete());

        consumer.start();

        await().atMost(TIMEOUT).untilAsserted(() -> verify(sqs).deleteMessageBatch(any(Consumer.class)));
        int before = receives.get();
        await().atMost(TIMEOUT).until(() -> receives.get() >= before + 2);
        assertThat(consumer.isRunning()).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void 처리_중_Error_가_나도_그_메시지만_그대로_두고_계속_받는다() {
        queueReturnsOnce(message("poison"));
        consumer = consumer(() -> 10, message -> {
            throw new AssertionError("깨진 메시지");
        });

        consumer.start();

        await().atMost(TIMEOUT).until(() -> receives.get() >= 3 && consumer.inFlight() == 0);
        verify(sqs, never()).deleteMessageBatch(any(Consumer.class));
        assertThat(visibilities).isEmpty();
        assertThat(consumer.isRunning()).isTrue();
    }

    @Test
    void 처리_중_메모리가_부족하면_새로_받지_않고_돌고_있다고_하지_않는다() {
        queueReturnsOnce(message("oom"));
        consumer = consumer(() -> 10, message -> {
            throw new OutOfMemoryError("흉내");
        });

        consumer.start();

        await().atMost(TIMEOUT).until(() -> !consumer.isRunning() && consumer.inFlight() == 0);
        assertThat(receivingStopped()).isTrue();
    }

    /** 지우는 작업만 죽고 받기가 남으면 지우지 못하는 메시지를 끝없이 다시 처리한다. */
    @Test
    @SuppressWarnings("unchecked")
    void 삭제_작업이_끝나면_받기도_멈춘다() {
        queueReturnsOnce(message("a"));
        given(sqs.deleteMessageBatch(any(Consumer.class))).willThrow(new OutOfMemoryError("흉내"));
        consumer = consumer(() -> 10, message -> MessageOutcome.delete());

        consumer.start();

        await().atMost(TIMEOUT).until(() -> !consumer.isRunning());
        assertThat(receivingStopped()).isTrue();
    }

    @Test
    void 돌려보낼_시간은_초로_올려_넘긴다() {
        queueReturnsOnce(message("a"));
        consumer = consumer(() -> 10, message -> MessageOutcome.returnAfter(Duration.ofMillis(1500)));

        consumer.start();

        await().atMost(TIMEOUT).until(() -> !visibilities.isEmpty());
        assertThat(visibilities).containsExactly(2);
    }

    @Test
    @SuppressWarnings("unchecked")
    void 받기가_실패하면_쉬었다_다시_받는다() {
        willAnswer(invocation -> {
            if (receives.incrementAndGet() <= 2) {
                throw SqsException.builder().message("queue down").build();
            }
            Thread.sleep(20);
            return ReceiveMessageResponse.builder().build();
        }).given(sqs).receiveMessage(any(Consumer.class));
        consumer = consumer(() -> 10, message -> MessageOutcome.delete());

        consumer.start();

        await().atMost(TIMEOUT).until(() -> receives.get() >= 3);
        assertThat(consumer.isRunning()).isTrue();
    }

    private BatchQueueConsumer consumer(IntSupplier freeSlots, BatchMessageHandler handler) {
        SqsQueueUrls queueUrls = new SqsQueueUrls(sqs,
                new SqsClientProperties(null, null, "test", "test", Map.of(), Duration.ofSeconds(3)));
        return new BatchQueueConsumer(sqs, queueUrls, SETTINGS, List.of(), freeSlots, handler);
    }

    /** 첫 받기에만 메시지를 돌려준다. 빈 받기는 조금 쉬어 대역 호출이 쏟아지지 않게 한다. */
    @SuppressWarnings("unchecked")
    private void queueReturnsOnce(Message... messages) {
        willAnswer(invocation -> {
            Consumer<ReceiveMessageRequest.Builder> request = invocation.getArgument(0);
            ReceiveMessageRequest.Builder builder = ReceiveMessageRequest.builder();
            request.accept(builder);
            requestedCounts.add(builder.build().maxNumberOfMessages());
            if (receives.incrementAndGet() == 1) {
                return ReceiveMessageResponse.builder().messages(messages).build();
            }
            Thread.sleep(20);
            return ReceiveMessageResponse.builder().build();
        }).given(sqs).receiveMessage(any(Consumer.class));
    }

    @SuppressWarnings("unchecked")
    private void deleteBatchAnswers(DeleteMessageBatchResponse response) {
        willAnswer(invocation -> {
            Consumer<DeleteMessageBatchRequest.Builder> request = invocation.getArgument(0);
            DeleteMessageBatchRequest.Builder builder = DeleteMessageBatchRequest.builder();
            request.accept(builder);
            deleted.add(builder.build().entries());
            return response;
        }).given(sqs).deleteMessageBatch(any(Consumer.class));
    }

    /** 진행 중이던 받기 한 번이 끝난 뒤로는 더 받지 않는다. */
    private boolean receivingStopped() {
        await().pollDelay(Duration.ofMillis(200)).until(() -> true);
        int before = receives.get();
        await().pollDelay(Duration.ofMillis(300)).until(() -> true);
        return receives.get() == before;
    }

    private static Message message(String body) {
        return Message.builder().messageId(body).receiptHandle("rh-" + body).body(body).build();
    }
}
