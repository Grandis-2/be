package com.grandis.nova.order.outbox;

import tools.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * 큐 메시지 본문 = 아웃박스 행 하나. 발행할 때 싸고(NV-79) 받을 때 푼다.
 *
 * aggregateId 는 보낸 쪽이 정한 대상이다. preorder 가 보내는 예약 이벤트는 예약 내부 id(orders.preorder_id)다.
 *
 * common:outbox 이전 시: preorder outbox.EventEnvelope 의 복사본이다. 그대로 공통으로 옮긴다.
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
