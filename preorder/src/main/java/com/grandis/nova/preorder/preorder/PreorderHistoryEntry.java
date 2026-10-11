package com.grandis.nova.preorder.preorder;

import java.time.Instant;
import java.util.UUID;

/** 모듈 밖에 내주는 예약 이력 한 줄. 순서는 시각이 아니라 eventSequence 다. */
public record PreorderHistoryEntry(
        UUID preorderId,
        long eventSequence,
        PreorderStatus fromStatus,
        PreorderStatus toStatus,
        EventActor actor,
        String reason,
        Instant createdAt
) {
}
