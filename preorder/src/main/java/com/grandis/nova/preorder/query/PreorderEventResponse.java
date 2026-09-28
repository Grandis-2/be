package com.grandis.nova.preorder.query;

import com.grandis.nova.preorder.preorder.EventActor;
import com.grandis.nova.preorder.preorder.PreorderEvent;
import com.grandis.nova.preorder.preorder.PreorderStatus;

import java.time.Instant;

/** 예약 이력 한 줄(openapi PreorderEvent). 순서는 시각이 아니라 eventSequence 다. */
public record PreorderEventResponse(
        long eventSequence,
        PreorderStatus fromStatus,
        PreorderStatus toStatus,
        EventActor actor,
        String reason,
        Instant createdAt
) {

    public static PreorderEventResponse from(PreorderEvent event) {
        return new PreorderEventResponse(event.getEventSequence(), event.getFromStatus(), event.getToStatus(),
                event.getActor(), event.getReason(), event.getCreatedAt());
    }
}
