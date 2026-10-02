package com.grandis.nova.preorder.integration.sqs;

import com.grandis.nova.common.sqs.SqsQueueUrls;
import com.grandis.nova.preorder.deadletter.DeadLetterRedriver;
import com.grandis.nova.preorder.deadletter.DeadLetters;
import com.grandis.nova.preorder.deadletter.IncomingDeadLetter;
import com.grandis.nova.preorder.event.PreorderEventDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;

import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 메시지를 지울지 다시 받을지. 큐 · DB 없이 소비기의 판단만 본다.
 * 이벤트 소비는 처리 함수가 던지는지만 본다 — 던지면 지우지 않고 늦추는 것은 common:sqs(RetryingQueueConsumer)의 규칙이다.
 */
class QueueConsumersTest {

    static final String QUEUE_URL = "http://queue";

    final SqsClient sqs = mock(SqsClient.class);
    final DeadLetters deadLetters = mock(DeadLetters.class);
    final PreorderEventDispatcher dispatcher = mock(PreorderEventDispatcher.class);

    DeadLetterConsumer deadLetterConsumer;
    PreorderEventMessageHandler eventHandler;

    @BeforeEach
    void setUp() {
        deadLetterConsumer = new DeadLetterConsumer(sqs, mock(SqsQueueUrls.class), deadLetters,
                new DeadLetterConsumerProperties(true, "preorder-events-dlq", 1, Duration.ofSeconds(5)),
                new PreorderEventConsumerProperties(true, "preorder-events", 1, 1, 10, Duration.ofSeconds(5),
                        Duration.ofSeconds(1), Duration.ofSeconds(1)));
        eventHandler = new PreorderEventMessageHandler(dispatcher, deadLetters);
    }

    @Test
    void DLQ_적재가_예외면_지우지_않는다_가시성_시간_뒤_다시_받는다() {
        given(deadLetters.record(any())).willThrow(new IllegalStateException("UNIQUE 충돌 또는 DB 장애"));

        deadLetterConsumer.handle(QUEUE_URL, message("7"));

        verify(sqs, never()).deleteMessage(anyDelete());
    }

    @Test
    void DLQ_적재가_끝났거나_이미_쌓인_메시지면_지운다() {
        given(deadLetters.record(any())).willReturn(true, false);

        deadLetterConsumer.handle(QUEUE_URL, message(null));
        deadLetterConsumer.handle(QUEUE_URL, message(null));

        verify(sqs, times(2)).deleteMessage(anyDelete());
    }

    /** 되돌린 메시지가 또 DLQ 로 오면 새 행이 앞선 행을 가리킨다. 받는 원래 큐 이름도 함께 적는다. */
    @Test
    void DLQ_메시지의_원래_큐와_앞선_DLQ_행_id_를_적는다() {
        given(deadLetters.record(any())).willReturn(true);

        deadLetterConsumer.handle(QUEUE_URL, message("7"));

        ArgumentCaptor<IncomingDeadLetter> recorded = ArgumentCaptor.forClass(IncomingDeadLetter.class);
        verify(deadLetters).record(recorded.capture());
        assertThat(recorded.getValue().sourceQueue()).isEqualTo("preorder-events");
        assertThat(recorded.getValue().redrivenFromId()).isEqualTo(7L);
    }

    @Test
    void 되돌린_메시지의_결과를_남기지_못하면_던져_지우지_않고_다시_받게_한다() {
        willThrow(new IllegalStateException("DB 장애")).given(deadLetters).markRedriveSucceeded(7L);

        assertThatThrownBy(() -> eventHandler.handle(message("7"))).isInstanceOf(IllegalStateException.class);
        verify(dispatcher).dispatch("{}");
    }

    @Test
    void 되돌린_메시지를_처리하면_결과를_남긴다() {
        eventHandler.handle(message("7"));

        verify(deadLetters).markRedriveSucceeded(7L);
    }

    @Test
    void 처리가_실패하면_되돌리기_결과를_남기지_않는다() {
        willThrow(new IllegalStateException("처리 실패")).given(dispatcher).dispatch("{}");

        assertThatThrownBy(() -> eventHandler.handle(message("7"))).isInstanceOf(IllegalStateException.class);
        verify(deadLetters, never()).markRedriveSucceeded(anyLong());
    }

    @Test
    void 되돌린_메시지가_아니거나_행_id_를_읽을_수_없으면_결과를_남기지_않는다() {
        eventHandler.handle(message(null));
        eventHandler.handle(message("not-a-number"));

        verify(dispatcher, times(2)).dispatch("{}");
        verify(deadLetters, never()).markRedriveSucceeded(anyLong());
    }

    private Message message(String deadLetterId) {
        Message.Builder builder = Message.builder().messageId("m-1").receiptHandle("r-1").body("{}");
        if (deadLetterId != null) {
            builder.messageAttributes(Map.of(DeadLetterRedriver.DEAD_LETTER_ID_ATTRIBUTE,
                    MessageAttributeValue.builder().dataType("Number").stringValue(deadLetterId).build()));
        }
        return builder.build();
    }

    @SuppressWarnings("unchecked")
    private Consumer<DeleteMessageRequest.Builder> anyDelete() {
        return any(Consumer.class);
    }
}
