package com.grandis.nova.payment.deadletter.application;

import com.grandis.nova.payment.deadletter.domain.DeadLetterBody;
import com.grandis.nova.payment.deadletter.domain.FailureReason;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DeadLetterBodyParserTest {

    final DeadLetterBodyParser parser = new DeadLetterBodyParser(JsonMapper.builder().findAndAddModules().build());

    @Test
    void 받는_종류의_봉투면_검색_칸을_채우고_되돌릴_수_있다() {
        DeadLetterBody body = parser.parse(envelope("ORDER_REFUND_REQUESTED"));

        assertThat(body.failureReason()).isEqualTo(FailureReason.PROCESSING_FAILED);
        assertThat(body.redrivable()).isTrue();
        assertThat(body.eventId()).isEqualTo("0b6f3c9e-2d41-4c8a-9f7e-5a1d2c3b4e5f");
        assertThat(body.aggregateType()).isEqualTo("ORDER");
        assertThat(body.aggregateId()).isEqualTo(UUID.fromString("00000000-0000-7000-8000-000000000007"));
    }

    @Test
    void 모르는_종류는_되돌려도_같은_결과라_되돌릴_수_없다() {
        DeadLetterBody body = parser.parse(envelope("SOMETHING_NEW"));

        assertThat(body.failureReason()).isEqualTo(FailureReason.UNKNOWN_EVENT_TYPE);
        assertThat(body.redrivable()).isFalse();
        assertThat(body.eventType()).isEqualTo("SOMETHING_NEW");
    }

    // 받는 종류는 모두 주문 이벤트다 — 분배기가 늘 거절하는 모양이라 되돌려도 같은 결과다
    @Test
    void 받는_종류인데_주문_aggregate_가_아니면_검색_칸은_채우고_되돌릴_수_없다() {
        DeadLetterBody otherAggregate = parser.parse(envelope("ORDER_REFUND_REQUESTED").replace("\"ORDER\"", "\"DRAW_ENTRY\""));
        DeadLetterBody noId = parser.parse("""
                {"eventId":"e","eventType":"ORDER_REFUND_REQUESTED","aggregateType":"ORDER","payload":{"amount":1}}""");

        assertThat(otherAggregate.failureReason()).isEqualTo(FailureReason.UNREADABLE_BODY);
        assertThat(otherAggregate.redrivable()).isFalse();
        assertThat(otherAggregate.eventType()).isEqualTo("ORDER_REFUND_REQUESTED");
        assertThat(otherAggregate.aggregateType()).isEqualTo("DRAW_ENTRY");
        assertThat(noId.failureReason()).isEqualTo(FailureReason.UNREADABLE_BODY);
    }

    @Test
    void 봉투로_읽을_수_없으면_검색_칸_없이_분류만_남긴다() {
        for (String broken : new String[]{"not-json", "[1,2]", "null", "{\"aggregateId\":\"x\"}"}) {
            DeadLetterBody body = parser.parse(broken);

            assertThat(body.failureReason()).as(broken).isEqualTo(FailureReason.UNREADABLE_BODY);
            assertThat(body.redrivable()).as(broken).isFalse();
            assertThat(body.eventType()).as(broken).isNull();
        }
    }

    @Test
    void 계약_길이를_넘는_검색_칸은_잘라_넣는다() {
        DeadLetterBody body = parser.parse(envelope("X".repeat(80)));

        assertThat(body.eventType()).hasSize(50);
    }

    private String envelope(String eventType) {
        return """
                {"eventId":"0b6f3c9e-2d41-4c8a-9f7e-5a1d2c3b4e5f","eventType":"%s","aggregateType":"ORDER",
                 "aggregateId":"00000000-0000-7000-8000-000000000007","occurredAt":"2026-10-04T01:00:00Z","payload":{"amount":15000}}
                """.formatted(eventType);
    }
}
