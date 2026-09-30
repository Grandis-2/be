package com.grandis.nova.preorder.deadletter.application;

import com.grandis.nova.preorder.deadletter.domain.DeadLetterBody;
import com.grandis.nova.preorder.deadletter.domain.FailureReason;
import com.grandis.nova.preorder.event.InboundEventType;
import com.grandis.nova.preorder.outbox.EventEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * DLQ 원문을 지금 코드로 읽는다. 지금 코드로도 읽지 못하거나 모르는 종류면 되돌려도 같은 결과다 —
 * 배포로 받게 되면 그때부터 되돌릴 수 있다.
 */
@Component
public class DeadLetterBodyParser {

    private final JsonMapper jsonMapper;

    public DeadLetterBodyParser(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public DeadLetterBody parse(String body) {
        EventEnvelope envelope;
        try {
            envelope = jsonMapper.readValue(body, EventEnvelope.class);
        } catch (JacksonException e) {
            return unreadable();
        }
        if (envelope == null) {
            return unreadable();
        }
        FailureReason reason = InboundEventType.isKnown(envelope.eventType())
                ? FailureReason.PROCESSING_FAILED : FailureReason.UNKNOWN_EVENT_TYPE;
        return new DeadLetterBody(truncate(envelope.eventId(), 36), truncate(envelope.eventType(), 50),
                truncate(envelope.aggregateType(), 30), envelope.aggregateId(), preorderToken(envelope.payload()),
                reason);
    }

    private DeadLetterBody unreadable() {
        return new DeadLetterBody(null, null, null, null, null, FailureReason.UNREADABLE_BODY);
    }

    private String preorderToken(JsonNode payload) {
        if (payload == null || !payload.path("preorderId").isString()) {
            return null;
        }
        return payload.path("preorderId").asString();
    }

    /** 검색 칸은 계약 길이를 넘는 값(깨진 본문)을 잘라 넣는다. 원문은 body 에 그대로 있다. */
    private String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
