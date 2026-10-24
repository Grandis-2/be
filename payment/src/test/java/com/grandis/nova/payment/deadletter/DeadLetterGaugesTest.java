package com.grandis.nova.payment.deadletter;

import com.grandis.nova.common.security.RevocationChecker;
import com.grandis.nova.payment.support.PaymentIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** DLQ 지표. 공유 DB 라 전체 수가 아니라 이 시험이 더한 만큼 늘었는지 본다. 종류는 받는 종류 · OTHER · UNKNOWN 으로 접힌다. */
@PaymentIntegrationTest
class DeadLetterGaugesTest {

    @Autowired
    DeadLetterGauges gauges;

    @Autowired
    DeadLetters deadLetters;

    @Autowired
    MeterRegistry registry;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @MockitoBean
    RevocationChecker revocationChecker;

    @Test
    void 되돌리기를_기다리는_수와_나이를_종류_사유별로_센다() {
        gauges.refresh();
        double refundBefore = byType("ORDER_REFUND_REQUESTED", "PROCESSING_FAILED");
        double otherBefore = byType("OTHER", "UNKNOWN_EVENT_TYPE");
        double unknownBefore = byType("UNKNOWN", "UNREADABLE_BODY");
        double totalBefore = registry.get(DeadLetterGauges.WAITING_METRIC).gauge().value();

        String oldest = record(event("ORDER_REFUND_REQUESTED"));
        record(event("SOMETHING_NEW_" + UUID.randomUUID().toString().substring(0, 8)));
        record(event("SOMETHING_ELSE_" + UUID.randomUUID().toString().substring(0, 8)));
        record("not-json");
        jdbcTemplate.update("UPDATE payment_dead_letter_events SET created_at = created_at - INTERVAL 2 HOUR"
                + " WHERE message_id = ?", oldest);
        gauges.refresh();

        assertThat(byType("ORDER_REFUND_REQUESTED", "PROCESSING_FAILED")).isEqualTo(refundBefore + 1);
        assertThat(byType("OTHER", "UNKNOWN_EVENT_TYPE")).isEqualTo(otherBefore + 2);
        assertThat(byType("UNKNOWN", "UNREADABLE_BODY")).isEqualTo(unknownBefore + 1);
        assertThat(registry.get(DeadLetterGauges.WAITING_METRIC).gauge().value()).as("합계")
                .isEqualTo(totalBefore + 4);
        assertThat(registry.get(DeadLetterGauges.OLDEST_AGE_METRIC).gauge().value()).as("초")
                .isGreaterThanOrEqualTo(7200);
    }

    private double byType(String eventType, String reason) {
        var gauge = registry.find(DeadLetterGauges.WAITING_BY_TYPE_METRIC)
                .tags("eventType", eventType, "reason", reason).gauge();
        return gauge == null ? 0 : gauge.value();
    }

    private String record(String body) {
        String messageId = UUID.randomUUID().toString();
        deadLetters.record(new IncomingDeadLetter("payment-events", messageId, body, 5, null, null));
        return messageId;
    }

    private static String event(String eventType) {
        return """
                {"eventId":"%s","eventType":"%s","aggregateType":"ORDER","aggregateId":"%s",
                 "occurredAt":"2026-10-04T01:00:00Z","payload":{"amount":15000}}
                """.formatted(UUID.randomUUID(), eventType, UUID.randomUUID());
    }
}
