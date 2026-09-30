package com.grandis.nova.preorder.deadletter;

import java.time.Instant;

/** @param preorderId 예약 공개 UUID. 예약이 없는 이벤트면 null */
record DeadLetterSummaryResponse(
        Long deadLetterId,
        String messageId,
        String eventId,
        String eventType,
        String preorderId,
        Long customerId,
        FailureReason failureReason,
        int receiveCount,
        DeadLetterStatus status,
        boolean redrivable,
        Long redrivenFromId,
        Instant sentAt,
        Instant createdAt,
        Instant updatedAt
) {

    static DeadLetterSummaryResponse from(DeadLetterView view) {
        DeadLetterEvent event = view.event();
        return new DeadLetterSummaryResponse(event.getId(), event.getMessageId(), event.getEventId(),
                event.getEventType(), view.preorderToken(), event.getCustomerId(), event.getFailureReason(),
                event.getReceiveCount(), event.getStatus(), view.redrivable(), event.getRedrivenFromId(),
                event.getSentAt(), event.getCreatedAt(), event.getUpdatedAt());
    }
}
