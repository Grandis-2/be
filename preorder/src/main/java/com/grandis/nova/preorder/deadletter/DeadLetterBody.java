package com.grandis.nova.preorder.deadletter;

import com.grandis.nova.preorder.event.InboundEventType;
import com.grandis.nova.preorder.outbox.EventEnvelope;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 원문을 지금 코드로 읽은 결과. 적재 때 분류와 검색 칸을 채우고, 되돌리기 전에 다시 읽어 되돌려도 되는지 가른다 —
 * 지금 코드로도 읽지 못하거나 모르는 종류면 되돌려도 같은 결과다. 배포로 받게 되면 그때부터 되돌릴 수 있다.
 *
 * @param preorderToken payload.preorderId(예약 공개 UUID). 없으면 null
 */
record DeadLetterBody(
        String eventId,
        String eventType,
        String aggregateType,
        Long aggregateId,
        String preorderToken,
        FailureReason failureReason
) {

    static DeadLetterBody parse(JsonMapper jsonMapper, String body) {
        EventEnvelope envelope;
        try {
            envelope = jsonMapper.readValue(body, EventEnvelope.class);
        } catch (JacksonException e) {
            return new DeadLetterBody(null, null, null, null, null, FailureReason.UNREADABLE_BODY);
        }
        if (envelope == null) {
            return new DeadLetterBody(null, null, null, null, null, FailureReason.UNREADABLE_BODY);
        }
        FailureReason reason = InboundEventType.isKnown(envelope.eventType())
                ? FailureReason.PROCESSING_FAILED : FailureReason.UNKNOWN_EVENT_TYPE;
        return new DeadLetterBody(truncate(envelope.eventId(), 36), truncate(envelope.eventType(), 50),
                truncate(envelope.aggregateType(), 30), envelope.aggregateId(), preorderToken(envelope.payload()),
                reason);
    }

    /** 되돌려서 달라질 수 있는가 — 읽을 수 있고 받는 종류일 때만. */
    boolean redrivable() {
        return failureReason == FailureReason.PROCESSING_FAILED;
    }

    private static String preorderToken(JsonNode payload) {
        if (payload == null || !payload.path("preorderId").isString()) {
            return null;
        }
        return payload.path("preorderId").asString();
    }

    /** 검색 칸은 계약 길이를 넘는 값(깨진 본문)을 잘라 넣는다. 원문은 body 에 그대로 있다. */
    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
