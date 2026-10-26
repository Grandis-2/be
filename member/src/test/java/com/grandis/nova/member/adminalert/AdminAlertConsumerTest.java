package com.grandis.nova.member.adminalert;

import com.grandis.nova.common.sqs.RetryingQueueConsumer;
import com.grandis.nova.common.sqs.testing.TestQueues;
import com.grandis.nova.member.support.SqsIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import software.amazon.awssdk.services.sqs.model.Message;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * 관리자 알림 큐 둘의 배선을 끝에서 끝으로 본다 — 실제 SQS 프로토콜(Floci)로 받아 저장하는지, 계약 위반이 이유와 함께 DLQ 로 가는지, 일시 오류가
 * 다시 받기로 끝나는지. 판정 규칙 자체는 AdminAlertWriterTest 가 본다.
 */
@SqsIntegrationTest
@DisplayName("관리자 알림 소비기 — admin-alert · admin-alert-grafana 큐 (Floci · 실제 MySQL)")
class AdminAlertConsumerTest {

    static final Duration TIMEOUT = Duration.ofSeconds(15);
    static final String EVENTS = "admin-alert";
    static final String GRAFANA = "admin-alert-grafana";

    @Autowired TestQueues queues;
    @Autowired ApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean AdminAlertWriter writer;
    @MockitoSpyBean GrafanaAlertReceiver grafana;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM admin_alerts");
    }

    @Test
    @DisplayName("설정(nova.sqs.consumer.enabled)이 소비기 둘로 이어진다")
    void consumersAreWired() {
        assertThat(context.getBean("adminAlertEventConsumer", RetryingQueueConsumer.class).isRunning()).isTrue();
        assertThat(context.getBean("adminAlertGrafanaConsumer", RetryingQueueConsumer.class).isRunning()).isTrue();
    }

    @Test
    @DisplayName("admin-alert 큐의 DRAW_SETTLED 가 저장되고 메시지는 지워진다")
    void storesEvent() {
        UUID draw = UUID.randomUUID();
        AdminAlertMessages.Event event = AdminAlertMessages.Event.draw("DRAW_SETTLED", draw, "2026-10-26T05:00:00Z");

        queues.send(EVENTS, event.body());

        await().atMost(TIMEOUT).until(() -> count("DRAW_SETTLED", draw.toString()) == 1);
        assertThat(queues.receive(EVENTS + "-dlq", m -> m.body().contains(event.eventId), Duration.ofSeconds(3))).isEmpty();
    }

    @Test
    @DisplayName("메시지 속성 없이 온 Grafana 메시지(SNS 원문 전달)도 받아 저장하고, 마지막 갱신 시각은 SQS 가 받은 시각(SentTimestamp)이다")
    void storesGrafanaWithoutAttributesAtSentTimestamp() {
        String body = AdminAlertMessages.Grafana.firing("SHIPPING_BACKLOG", "2026-10-26T05:00:00Z").metric("total", "58").body();
        Instant before = Instant.now().minusSeconds(1).truncatedTo(ChronoUnit.MILLIS);

        queues.send(GRAFANA, body);

        await().atMost(TIMEOUT).until(() -> count("SHIPPING_BACKLOG", "") == 1);
        ArgumentCaptor<Instant> sentAt = ArgumentCaptor.forClass(Instant.class);
        verify(grafana).receive(eq(body), sentAt.capture());
        assertThat(sentAt.getValue()).as("SQS 가 붙인 시각").isNotNull().isBetween(before, Instant.now());
        LocalDateTime lastUpdated = jdbc.queryForObject("SELECT last_updated_at FROM admin_alerts WHERE type = 'SHIPPING_BACKLOG'",
                LocalDateTime.class);
        assertThat(lastUpdated.toInstant(ZoneOffset.UTC)).isEqualTo(sentAt.getValue());
    }

    @Test
    @DisplayName("계약 위반은 다시 받지 않고 이유를 붙여 DLQ 에 바로 넣는다 — 이벤트는 INVALID_PAYLOAD, Grafana 는 INVALID_ALERT")
    void contractViolationsGoStraightToDeadLetterQueue() {
        String badEvent = AdminAlertMessages.Event.draw("DRAW_SETTLED", UUID.randomUUID(), "2026-10-26T05:00:00Z")
                .with(e -> e.payload().put("metrics", "x")).body();
        String badAlert = AdminAlertMessages.Grafana.firing("REGISTER_FAILED", "2026-10-26T05:00:00Z")
                .with(g -> g.labels().remove("admin_alert_type")).metric("marker", UUID.randomUUID().toString()).body();

        queues.send(EVENTS, badEvent);
        queues.send(GRAFANA, badAlert);

        Message deadEvent = queues.receive(EVENTS + "-dlq", m -> m.body().equals(badEvent), TIMEOUT).orElseThrow();
        assertThat(deadEvent.messageAttributes().get(AdminAlertDeadLetters.REASON).stringValue()).isEqualTo("INVALID_PAYLOAD");
        Message deadAlert = queues.receive(GRAFANA + "-dlq", m -> m.body().equals(badAlert), TIMEOUT).orElseThrow();
        assertThat(deadAlert.messageAttributes().get(AdminAlertDeadLetters.REASON).stringValue()).isEqualTo("INVALID_ALERT");
    }

    @Test
    @DisplayName("일시 오류(DB 끊김)는 DLQ 에 바로 넣지 않고 늦춰 다시 받다가, 재수신 한도를 넘기면 재드라이브 정책이 옮긴다")
    void transientFailureIsRetriedThenRedriven() {
        UUID draw = UUID.randomUUID();
        String body = AdminAlertMessages.Event.draw("DRAW_DELAYED", draw, "2026-10-26T05:00:00Z").body();
        doThrow(new DataAccessResourceFailureException("DB 연결 끊김"))
                .when(writer).apply(argThat(signal -> signal != null && draw.toString().equals(signal.targetId())), any());

        queues.send(EVENTS, body);

        Message dead = queues.receive(EVENTS + "-dlq", m -> m.body().equals(body), Duration.ofSeconds(30)).orElseThrow();
        assertThat(dead.messageAttributes()).doesNotContainKey(AdminAlertDeadLetters.REASON);
        assertThat(count("DRAW_DELAYED", draw.toString())).isZero();
    }

    private long count(String type, String targetId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM admin_alerts WHERE type = ? AND target_id = ?", Long.class, type, targetId);
        return count == null ? 0 : count;
    }
}
