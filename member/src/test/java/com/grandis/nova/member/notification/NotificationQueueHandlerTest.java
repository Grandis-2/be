package com.grandis.nova.member.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import software.amazon.awssdk.services.sqs.model.Message;

/** 실패를 둘로 가르는 규칙 — 계약 위반은 DLQ 로 바로, 그 밖은 던져 다시 받는다. */
@DisplayName("알림 메시지 처리 — 계약 위반과 일시 오류를 가른다")
class NotificationQueueHandlerTest {

    final NotificationReceiver receiver = mock(NotificationReceiver.class);
    final NotificationDeadLetters deadLetters = mock(NotificationDeadLetters.class);
    final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    final NotificationQueueHandler handler = new NotificationQueueHandler(receiver, deadLetters, meterRegistry);
    final Message message = Message.builder().messageId("m-1").body("{}").build();

    @Test
    @DisplayName("계약 위반은 이유를 붙여 DLQ 에 넣고 돌아온다 — 소비기가 원본을 지운다")
    void rejectedGoesToDeadLetterQueue() {
        doThrow(new NotificationRejected(NotificationRejected.Reason.INVALID_PAYLOAD, "빈 제목"))
                .when(receiver).receive("{}");

        handler.handle(message);

        verify(deadLetters).send(message, NotificationRejected.Reason.INVALID_PAYLOAD);
        assertThat(meterRegistry.counter(NotificationQueueHandler.REJECTED_METRIC, "reason", "INVALID_PAYLOAD").count())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("DB 순단 같은 일시 오류는 DLQ 에 넣지 않고 던진다 — 늦춰 다시 받는다")
    void transientFailureIsRetried() {
        doThrow(new DataAccessResourceFailureException("연결 끊김")).when(receiver).receive("{}");

        assertThatThrownBy(() -> handler.handle(message)).isInstanceOf(DataAccessResourceFailureException.class);

        verify(deadLetters, never()).send(any(), any());
    }

    @Test
    @DisplayName("DLQ 에 넣지 못하면 던진다 — 원본이 남아 계약 위반 메시지도 잃지 않는다")
    void deadLetterFailureKeepsOriginal() {
        doThrow(new NotificationRejected(NotificationRejected.Reason.UNREADABLE, "JSON 아님")).when(receiver).receive("{}");
        doThrow(new IllegalStateException("SQS 실패")).when(deadLetters).send(message, NotificationRejected.Reason.UNREADABLE);

        assertThatThrownBy(() -> handler.handle(message)).isInstanceOf(IllegalStateException.class);

        assertThat(meterRegistry.find(NotificationQueueHandler.REJECTED_METRIC).counter()).isNull();
    }
}
