package com.grandis.nova.common.message;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 봉투의 JSON 모양을 고정한다. preorder · order · 대기열(별도 저장소)이 이 모양을 읽는다 —
 * 칸 이름이 바뀌거나 payload 가 문자열로 한 번 더 싸이면 받는 쪽이 풀지 못한다.
 */
class EventEnvelopeJsonTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void 여섯_칸이_순서대로_나가고_시각은_ISO_문자열_payload_는_객체다() {
        EventEnvelope envelope = new EventEnvelope("e-1", "PREORDER_ORDER_SETTLED", "PREORDER", 7L,
                Instant.parse("2026-10-01T01:02:03.123456Z"), mapper.readTree("{\"preorderId\":\"p-1\"}"));

        JsonNode json = mapper.valueToTree(envelope);

        assertThat(json.propertyNames()).containsExactly(
                "eventId", "eventType", "aggregateType", "aggregateId", "occurredAt", "payload");
        assertThat(json.get("aggregateId").isNumber()).isTrue();
        assertThat(json.get("occurredAt").asString()).isEqualTo("2026-10-01T01:02:03.123456Z");
        assertThat(json.get("payload").isObject()).isTrue();
        assertThat(json.get("payload").get("preorderId").asString()).isEqualTo("p-1");
    }

    @Test
    void 받은_본문을_그대로_푼다() {
        String body = """
                {"eventId":"e-1","eventType":"PREORDER_CANCEL_REQUESTED","aggregateType":"PREORDER","aggregateId":7,
                 "occurredAt":"2026-10-01T01:02:03.123456Z","payload":{"cancelSequence":3}}""";

        EventEnvelope envelope = mapper.readValue(body, EventEnvelope.class);

        assertThat(envelope.aggregateId()).isEqualTo(7L);
        assertThat(envelope.occurredAt()).isEqualTo(Instant.parse("2026-10-01T01:02:03.123456Z"));
        assertThat(envelope.payload().get("cancelSequence").asLong()).isEqualTo(3L);
    }
}
