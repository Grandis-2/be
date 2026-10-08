package com.grandis.nova.common.message;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * 큐 메시지 본문 = 아웃박스 행 하나. 발행할 때 싸고 받을 때 푼다.
 *
 * JSON 모양(칸 이름 · 순서 · 형)이 계약이다. 모든 서비스가 같은 모양을 읽으므로 한쪽만 바꾸지 않는다.
 * aggregateId 는 보낸 쪽이 정한 대상의 UUID 다(문자열로 실린다). preorder 가 보내는 예약 이벤트는 예약 내부 id 다.
 *
 * @param eventId 아웃박스 event_id. 같은 메시지가 두 번 올 수 있다
 */
public record EventEnvelope(
        String eventId,
        String eventType,
        String aggregateType,
        UUID aggregateId,
        Instant occurredAt,
        JsonNode payload
) {
}
