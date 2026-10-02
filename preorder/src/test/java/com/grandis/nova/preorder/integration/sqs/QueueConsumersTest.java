package com.grandis.nova.preorder.integration.sqs;

import com.grandis.nova.common.sqs.SqsQueueUrls;
import com.grandis.nova.preorder.deadletter.DeadLetterRedriver;
import com.grandis.nova.preorder.deadletter.DeadLetters;
import com.grandis.nova.preorder.event.PreorderEventDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;

import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** 메시지를 지울지 다시 받을지. 큐 · DB 없이 소비기의 판단만 본다. */
class QueueConsumersTest {

    static final String QUEUE_URL = "http://queue";

    final SqsClient sqs = mock(SqsClient.class);
    final DeadLetters deadLetters = mock(DeadLetters.class);
    final PreorderEventDispatcher dispatcher = mock(PreorderEventDispatcher.class);
    final SqsProperties properties = new SqsProperties("ap-northeast-2", null, "test", "test", Duration.ofSeconds(3),
            new SqsProperties.Consumer(true, "preorder-events", 1, 1, 10, Duration.ofSeconds(5), Duration.ofSeconds(1),
                    Duration.ofSeconds(1)),
            new SqsProperties.DeadLetter(true, "preorder-events-dlq", 1, Duration.ofSeconds(5)));

    DeadLetterConsumer deadLetterConsumer;
    PreorderEventConsumer eventConsumer;

    @BeforeEach
    void setUp() {
        SqsQueueUrls queueUrls = mock(SqsQueueUrls.class);
        deadLetterConsumer = new DeadLetterConsumer(sqs, queueUrls, deadLetters, properties);
        eventConsumer = new PreorderEventConsumer(sqs, queueUrls, dispatcher, deadLetters, properties);
    }

    @Test
    void DLQ_적재가_예외면_지우지_않는다_가시성_시간_뒤_다시_받는다() {
        given(deadLetters.record(any())).willThrow(new IllegalStateException("UNIQUE 충돌 또는 DB 장애"));

        deadLetterConsumer.handle(QUEUE_URL, message(null));

        verify(sqs, never()).deleteMessage(anyDelete());
    }

    @Test
    void DLQ_적재가_끝났거나_이미_쌓인_메시지면_지운다() {
        given(deadLetters.record(any())).willReturn(true, false);

        deadLetterConsumer.handle(QUEUE_URL, message(null));
        deadLetterConsumer.handle(QUEUE_URL, message(null));

        verify(sqs, times(2)).deleteMessage(anyDelete());
    }

    @Test
    void 되돌린_메시지의_결과를_남기지_못하면_지우지_않고_다시_받는다() {
        willThrow(new IllegalStateException("DB 장애")).given(deadLetters).markRedriveSucceeded(7L);

        eventConsumer.handle(QUEUE_URL, message(7L));

        verify(sqs, never()).deleteMessage(anyDelete());
        verify(sqs).changeMessageVisibility(anyVisibility());
    }

    @Test
    void 되돌린_메시지를_처리하면_결과를_남기고_지운다() {
        eventConsumer.handle(QUEUE_URL, message(7L));

        verify(deadLetters).markRedriveSucceeded(7L);
        verify(sqs).deleteMessage(anyDelete());
    }

    private Message message(Long deadLetterId) {
        Message.Builder builder = Message.builder().messageId("m-1").receiptHandle("r-1").body("{}");
        if (deadLetterId != null) {
            builder.messageAttributes(Map.of(DeadLetterRedriver.DEAD_LETTER_ID_ATTRIBUTE,
                    MessageAttributeValue.builder().dataType("Number").stringValue(deadLetterId.toString()).build()));
        }
        return builder.build();
    }

    @SuppressWarnings("unchecked")
    private Consumer<DeleteMessageRequest.Builder> anyDelete() {
        return any(Consumer.class);
    }

    @SuppressWarnings("unchecked")
    private Consumer<ChangeMessageVisibilityRequest.Builder> anyVisibility() {
        return any(Consumer.class);
    }
}
