package com.grandis.nova.preorder.deadletter.api;

import com.grandis.nova.preorder.deadletter.application.DeadLetterListItem;
import com.grandis.nova.preorder.deadletter.application.DeadLetterView;
import com.grandis.nova.preorder.deadletter.domain.DeadLetterEvent;
import com.grandis.nova.preorder.deadletter.domain.DeadLetterStatus;
import com.grandis.nova.preorder.deadletter.domain.DeadLetterSummary;
import com.grandis.nova.preorder.deadletter.domain.FailureReason;

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

    public static DeadLetterSummaryResponse from(DeadLetterListItem item) {
        DeadLetterSummary summary = item.summary();
        return new DeadLetterSummaryResponse(summary.getId(), summary.getMessageId(), summary.getEventId(),
                summary.getEventType(), item.preorderToken(), summary.getCustomerId(), summary.getFailureReason(),
                summary.getReceiveCount(), summary.getStatus(), item.redrivable(), summary.getRedrivenFromId(),
                summary.getSentAt(), summary.getCreatedAt(), summary.getUpdatedAt());
    }

    public static DeadLetterSummaryResponse from(DeadLetterView view) {
        DeadLetterEvent event = view.event();
        return new DeadLetterSummaryResponse(event.getId(), event.getMessageId(), event.getEventId(),
                event.getEventType(), view.preorderToken(), event.getCustomerId(), event.getFailureReason(),
                event.getReceiveCount(), event.getStatus(), view.redrivable(), event.getRedrivenFromId(),
                event.getSentAt(), event.getCreatedAt(), event.getUpdatedAt());
    }
}
