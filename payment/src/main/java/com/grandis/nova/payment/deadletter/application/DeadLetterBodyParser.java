package com.grandis.nova.payment.deadletter.application;

import com.grandis.nova.common.message.EventEnvelope;
import com.grandis.nova.payment.deadletter.domain.DeadLetterBody;
import com.grandis.nova.payment.deadletter.domain.FailureReason;
import com.grandis.nova.payment.event.InboundEventType;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * DLQ 원문을 지금 코드로 읽는다. 지금 코드로도 읽지 못하거나 모르는 종류면 되돌려도 같은 결과다 —
 * 배포로 받게 되면 그때부터 되돌릴 수 있다. 받는 종류는 모두 주문 이벤트라, 받는 종류인데 주문 aggregate(ORDER · id)가 아니면
 * 분배기가 늘 거절하는 모양이므로 UNREADABLE_BODY 로 둔다(검색 칸은 채운다).
 */
@Component
public class DeadLetterBodyParser {

    private final JsonMapper jsonMapper;

    DeadLetterBodyParser(JsonMapper jsonMapper) {
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
        FailureReason reason = !InboundEventType.isKnown(envelope.eventType()) ? FailureReason.UNKNOWN_EVENT_TYPE
                : isOrderEvent(envelope) ? FailureReason.PROCESSING_FAILED : FailureReason.UNREADABLE_BODY;
        return new DeadLetterBody(truncate(envelope.eventId(), 36), truncate(envelope.eventType(), 50),
                truncate(envelope.aggregateType(), 30), envelope.aggregateId(), reason);
    }

    private static boolean isOrderEvent(EventEnvelope envelope) {
        return InboundEventType.ORDER_AGGREGATE.equals(envelope.aggregateType()) && envelope.aggregateId() != null;
    }

    private DeadLetterBody unreadable() {
        return new DeadLetterBody(null, null, null, null, FailureReason.UNREADABLE_BODY);
    }

    /** 검색 칸은 계약 길이를 넘는 값(깨진 본문)을 잘라 넣는다. 원문은 body 에 그대로 있다. */
    private String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
