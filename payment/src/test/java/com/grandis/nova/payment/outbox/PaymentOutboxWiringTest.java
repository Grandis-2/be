package com.grandis.nova.payment.outbox;

import com.grandis.nova.common.outbox.MessageTransport;
import com.grandis.nova.common.outbox.OutboundMessage;
import com.grandis.nova.common.outbox.OutboxWriter;
import com.grandis.nova.payment.domain.model.PaymentTransaction;
import com.grandis.nova.payment.support.PaymentFixtures;
import com.grandis.nova.payment.support.PaymentIntegrationTest;
import com.grandis.nova.payment.vo.Money;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willAnswer;

/**
 * payment 가 공통 아웃박스에 제대로 엮였는가 — 실제 마이그레이션의 payment_outbox_events 위에서 표 · 종류 · 목적지 · 봉투를 본다.
 * 기록 · 발행 · 릴레이 동작 자체는 common:outbox 시험이 본다.
 */
@PaymentIntegrationTest
@DirtiesContext
class PaymentOutboxWiringTest {

    static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Autowired
    OutboxWriter writer;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    JsonMapper jsonMapper;

    @MockitoBean
    MessageTransport transport;

    final Queue<OutboundMessage> sent = new ConcurrentLinkedQueue<>();

    @Test
    void settledResultGoesToOrderEventsFromPaymentTable() {
        willAnswer(invocation -> sent.add(invocation.getArgument(0))).given(transport).send(any());
        PaymentTransaction attempt = PaymentTransaction.openCapture(PaymentFixtures.newOrderTarget(), Money.won(3000),
                Instant.now());

        Long id = transactionTemplate.execute(status ->
                writer.append(OrderPaymentSettled.approved(attempt, Instant.parse("2026-10-02T00:00:00Z"))));
        String eventId = jdbcTemplate.queryForObject("SELECT event_id FROM payment_outbox_events WHERE id = ?",
                String.class, id);

        await().atMost(TIMEOUT).until(() -> sentOf(eventId).isPresent());
        OutboundMessage message = sentOf(eventId).orElseThrow();
        assertThat(message.destination()).isEqualTo("order-events");
        JsonNode body = jsonMapper.readTree(message.body());
        assertThat(body.get("eventType").asString()).isEqualTo("ORDER_PAYMENT_SETTLED");
        assertThat(body.get("aggregateType").asString()).isEqualTo("ORDER");
        assertThat(body.get("aggregateId").asString()).isEqualTo(attempt.target().id().toString());
        assertThat(body.get("payload").get("result").asString()).isEqualTo("APPROVED");
    }

    private Optional<OutboundMessage> sentOf(String eventId) {
        return sent.stream().filter(message -> message.eventId().equals(eventId)).findFirst();
    }
}
