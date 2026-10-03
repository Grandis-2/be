package com.grandis.nova.common.outbox;

import java.time.Instant;

/** 아웃박스 행 하나. 이 패키지 밖으로 나가지 않는다 — 밖에는 행 id 만 준다. */
record OutboxRow(
        Long id,
        String eventId,
        String aggregateType,
        Long aggregateId,
        String eventType,
        String payload,
        int publishAttempts,
        Instant createdAt,
        Instant publishedAt
) {
}
