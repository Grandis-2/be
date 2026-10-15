package com.grandis.nova.member.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.grandis.nova.common.sqs.RetryingQueueConsumer;
import com.grandis.nova.common.sqs.testing.TestQueues;
import com.grandis.nova.member.support.SqsIntegrationTest;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import software.amazon.awssdk.services.sqs.model.Message;

/**
 * notification 큐 배선을 끝에서 끝으로 본다 — 실제 SQS 프로토콜(Floci)로 받아 저장하는지, 계약 위반이 DLQ 로 가는지.
 * 받기 · 지우기 · 다시 보이기 자체는 common:sqs 시험이 본다.
 */
@SqsIntegrationTest
@DisplayName("알림 소비기 — notification 큐에서 받아 저장한다 (Floci · 실제 MySQL)")
class NotificationConsumerTest {

    static final Duration TIMEOUT = Duration.ofSeconds(15);
    static final String QUEUE = "notification";

    @Autowired TestQueues queues;
    @Autowired ApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean NotificationWriter writer;

    @Test
    @DisplayName("설정(nova.sqs.consumer.enabled)이 공통 소비기로 이어진다")
    void consumerIsWired() {
        assertThat(context.getBean("notificationConsumer", RetryingQueueConsumer.class).isRunning()).isTrue();
    }

    @Test
    @DisplayName("큐에 넣은 알림 요청이 받는 회원마다 한 행으로 저장되고 메시지는 지워진다")
    void storesFromQueue() {
        NotificationMessages message = new NotificationMessages(UUID.randomUUID(), UUID.randomUUID());

        queues.send(QUEUE, message.body());

        await().atMost(TIMEOUT).until(() -> rows(message.eventId) == 2);
        assertThat(queues.receive(QUEUE + "-dlq", m -> m.body().contains(message.eventId), Duration.ofSeconds(3))).isEmpty();
    }

    @Test
    @DisplayName("계약 위반(빈 제목)은 다시 받지 않고 이유를 붙여 DLQ 에 바로 넣는다")
    void contractViolationGoesStraightToDeadLetterQueue() {
        NotificationMessages message = new NotificationMessages(UUID.randomUUID()).with(m -> m.payload().put("title", ""));
        String body = message.body();

        queues.send(QUEUE, body);

        Message dead = queues.receive(QUEUE + "-dlq", m -> m.body().equals(body), TIMEOUT).orElseThrow();
        // 이유 속성은 소비기가 직접 넣을 때만 붙는다 — 재수신 한도를 채워 재드라이브 정책이 옮긴 것이 아니다
        assertThat(dead.messageAttributes().get(NotificationDeadLetters.REASON).stringValue())
                .isEqualTo("INVALID_PAYLOAD");
        assertThat(rows(message.eventId)).isZero();
    }

    @Test
    @DisplayName("일시 오류(DB 끊김)는 DLQ 에 바로 넣지 않고 늦춰 다시 받다가, 재수신 한도를 넘기면 재드라이브 정책이 옮긴다")
    void transientFailureIsRetriedThenRedriven() {
        NotificationMessages message = new NotificationMessages(UUID.randomUUID());
        String body = message.body();
        doThrow(new DataAccessResourceFailureException("DB 연결 끊김"))
                .when(writer).store(argThat(incoming -> message.eventId.equals(incoming.eventId())));

        queues.send(QUEUE, body);

        Message dead = queues.receive(QUEUE + "-dlq", m -> m.body().equals(body), TIMEOUT.multipliedBy(2)).orElseThrow();
        // 이유 속성이 없다 — 소비기가 넣은 것이 아니라 재수신 한도(시험 2회)를 넘겨 큐가 옮겼다. 원인이 풀리면 redrive 한다
        assertThat(dead.messageAttributes()).doesNotContainKey(NotificationDeadLetters.REASON);
        verify(writer, atLeast(2)).store(argThat(incoming -> message.eventId.equals(incoming.eventId())));
        assertThat(rows(message.eventId)).isZero();
    }

    private int rows(String eventId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE event_id = ?", Integer.class, eventId);
    }
}
