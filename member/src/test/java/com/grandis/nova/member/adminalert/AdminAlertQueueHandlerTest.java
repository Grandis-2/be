package com.grandis.nova.member.adminalert;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import software.amazon.awssdk.services.sqs.model.Message;

import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** 실패를 둘로 가르는 규칙 — 계약 위반은 DLQ 로 바로, 그 밖은 던져 다시 받는다(회원 알림의 처리기 시험과 같다). */
@DisplayName("관리자 알림 메시지 처리 — 계약 위반과 일시 오류를 가른다")
class AdminAlertQueueHandlerTest {

    @SuppressWarnings("unchecked")
    final Consumer<Message> receiver = mock(Consumer.class);
    final AdminAlertDeadLetters deadLetters = mock(AdminAlertDeadLetters.class);
    final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    final AdminAlertQueueHandler handler = new AdminAlertQueueHandler("admin-alert-grafana", receiver, deadLetters, meterRegistry);
    final Message message = Message.builder().messageId("m-1").body("{}").build();

    @Test
    @DisplayName("계약 위반은 이유를 붙여 DLQ 에 넣고 돌아온다 — 소비기가 원본을 지운다")
    void rejectedGoesToDeadLetterQueue() {
        doThrow(new AdminAlertRejected(AdminAlertRejected.Reason.INVALID_ALERT, "라벨 없음")).when(receiver).accept(message);

        handler.handle(message);

        verify(deadLetters).send(message, AdminAlertRejected.Reason.INVALID_ALERT);
        assertThat(meterRegistry.counter(AdminAlertQueueHandler.REJECTED_METRIC, "queue", "admin-alert-grafana", "reason", "INVALID_ALERT")
                .count()).isEqualTo(1);
    }

    @Test
    @DisplayName("DB 순단 같은 일시 오류는 DLQ 에 넣지 않고 던진다 — 늦춰 다시 받는다")
    void transientFailureIsRetried() {
        doThrow(new DataAccessResourceFailureException("연결 끊김")).when(receiver).accept(message);

        assertThatThrownBy(() -> handler.handle(message)).isInstanceOf(DataAccessResourceFailureException.class);
        verify(deadLetters, never()).send(any(), any());
    }

    @Test
    @DisplayName("교착(MySQL 1213 → CannotAcquireLockException)으로 되돌려진 쪽도 일시 오류다 — DLQ 에 넣지 않고 던져 다시 받는다")
    void deadlockIsRetried() {
        doThrow(new CannotAcquireLockException("Deadlock found when trying to get lock")).when(receiver).accept(message);

        assertThatThrownBy(() -> handler.handle(message)).isInstanceOf(CannotAcquireLockException.class);
        verify(deadLetters, never()).send(any(), any());
    }

    @Test
    @DisplayName("DLQ 에 넣지 못하면 던진다 — 원본이 남아 계약 위반 메시지도 잃지 않는다")
    void deadLetterFailureKeepsOriginal() {
        doThrow(new AdminAlertRejected(AdminAlertRejected.Reason.UNREADABLE, "JSON 아님")).when(receiver).accept(message);
        doThrow(new IllegalStateException("SQS 실패")).when(deadLetters).send(message, AdminAlertRejected.Reason.UNREADABLE);

        assertThatThrownBy(() -> handler.handle(message)).isInstanceOf(IllegalStateException.class);
        assertThat(meterRegistry.find(AdminAlertQueueHandler.REJECTED_METRIC).counter()).isNull();
    }
}
