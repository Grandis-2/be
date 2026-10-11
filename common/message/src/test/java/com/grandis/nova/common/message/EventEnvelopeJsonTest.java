package com.grandis.nova.common.message;

import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 봉투의 JSON 모양을 고정한다. 모든 서비스가 이 모양을 읽는다 —
 * 칸 이름이 바뀌거나 payload 가 문자열로 한 번 더 싸이면 받는 쪽이 풀지 못한다.
 */
class EventEnvelopeJsonTest {

    private static final UUID AGGREGATE_ID = UUID.fromString("0199a3f2-7c4e-7a10-8b2d-3f4e5a6b7c8d");

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void 여섯_칸이_순서대로_나가고_시각은_ISO_문자열_payload_는_객체다() {
        EventEnvelope envelope = new EventEnvelope("e-1", "PREORDER_ORDER_SETTLED", "PREORDER", AGGREGATE_ID,
                Instant.parse("2026-10-01T01:02:03.123456Z"), mapper.readTree("{\"preorderId\":\"p-1\"}"));

        JsonNode json = mapper.valueToTree(envelope);

        assertThat(json.propertyNames()).containsExactly(
                "eventId", "eventType", "aggregateType", "aggregateId", "occurredAt", "payload");
        assertThat(json.get("aggregateId").asString()).isEqualTo("0199a3f2-7c4e-7a10-8b2d-3f4e5a6b7c8d");
        assertThat(json.get("occurredAt").asString()).isEqualTo("2026-10-01T01:02:03.123456Z");
        assertThat(json.get("payload").isObject()).isTrue();
        assertThat(json.get("payload").get("preorderId").asString()).isEqualTo("p-1");
    }

    @Test
    void 받은_본문을_그대로_푼다() {
        String body = """
                {"eventId":"e-1","eventType":"PREORDER_CANCEL_REQUESTED","aggregateType":"PREORDER",
                 "aggregateId":"0199a3f2-7c4e-7a10-8b2d-3f4e5a6b7c8d",
                 "occurredAt":"2026-10-01T01:02:03.123456Z","payload":{"cancelSequence":3}}""";

        EventEnvelope envelope = mapper.readValue(body, EventEnvelope.class);

        assertThat(envelope.aggregateId()).isEqualTo(AGGREGATE_ID);
        assertThat(envelope.occurredAt()).isEqualTo(Instant.parse("2026-10-01T01:02:03.123456Z"));
        assertThat(envelope.payload().get("cancelSequence").asLong()).isEqualTo(3L);
    }

    /** 깨진 본문은 예외로 올라간다 — 소비기가 지우지 않아 재수신 한도를 넘으면 DLQ 로 간다. 조용히 빈 봉투가 되면 안 된다. */
    @Test
    void 잘린_본문은_풀지_않는다() {
        String truncated = "{\"eventId\":\"e-1\",\"eventType\":\"PREORDER_CANCEL_REQUESTED\",\"aggregateId\":\"0199a3f2-7c4e-7a10-8b2d-3f4e5a6b7c8d\",";

        assertThatThrownBy(() -> mapper.readValue(truncated, EventEnvelope.class)).isInstanceOf(JacksonException.class);
    }

    @Test
    void 시각이_ISO_형식이_아니면_풀지_않는다() {
        String body = """
                {"eventId":"e-1","eventType":"PREORDER_CANCEL_REQUESTED","aggregateType":"PREORDER",
                 "aggregateId":"0199a3f2-7c4e-7a10-8b2d-3f4e5a6b7c8d",
                 "occurredAt":"2026-10-01 01:02:03","payload":{}}""";

        assertThatThrownBy(() -> mapper.readValue(body, EventEnvelope.class)).isInstanceOf(JacksonException.class);
    }
}
