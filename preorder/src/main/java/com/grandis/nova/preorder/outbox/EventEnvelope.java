package com.grandis.nova.preorder.outbox;

import tools.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * 큐 메시지 본문 = 아웃박스 행 하나. 발행할 때 싸고 받을 때 푼다.
 *
 * @param eventId 아웃박스 event_id. 같은 메시지가 두 번 올 수 있다
 */
public record EventEnvelope(
        String eventId,
        String eventType,
        String aggregateType,
        Long aggregateId,
        Instant occurredAt,
        JsonNode payload
) {
}
