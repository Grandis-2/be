package com.grandis.nova.catalog.outbox;

import tools.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * 큐 메시지 본문 = 아웃박스 행 하나. preorder · order 의 봉투와 같은 모양이라 받는 쪽이 같은 방식으로 푼다.
 *
 * @param eventId 아웃박스 event_id. 같은 메시지가 두 번 갈 수 있다 — 받는 쪽은 두 번 받아도 결과가 같아야 한다
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
