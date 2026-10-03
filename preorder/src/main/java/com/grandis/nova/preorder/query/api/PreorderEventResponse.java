package com.grandis.nova.preorder.query.api;

import com.grandis.nova.preorder.preorder.EventActor;
import com.grandis.nova.preorder.preorder.PreorderHistoryEntry;
import com.grandis.nova.preorder.preorder.PreorderStatus;

import java.time.Instant;

/** 예약 이력 한 줄(openapi PreorderEvent). 순서는 시각이 아니라 eventSequence 다. */
record PreorderEventResponse(
        long eventSequence,
        PreorderStatus fromStatus,
        PreorderStatus toStatus,
        EventActor actor,
        String reason,
        Instant createdAt
) {

    public static PreorderEventResponse from(PreorderHistoryEntry event) {
        return new PreorderEventResponse(event.eventSequence(), event.fromStatus(), event.toStatus(),
                event.actor(), event.reason(), event.createdAt());
    }
}
