package com.grandis.nova.payment.deadletter.api;

import com.grandis.nova.payment.deadletter.application.DeadLetterListItem;
import com.grandis.nova.payment.deadletter.application.DeadLetterView;
import com.grandis.nova.payment.deadletter.domain.DeadLetterEvent;
import com.grandis.nova.payment.deadletter.domain.DeadLetterStatus;
import com.grandis.nova.payment.deadletter.domain.DeadLetterSummary;
import com.grandis.nova.payment.deadletter.domain.FailureReason;

import java.time.Instant;
import java.util.UUID;

/** @param orderId 주문 이벤트(aggregate ORDER)면 주문 id, 아니면 null */
record DeadLetterSummaryResponse(
        UUID deadLetterId,
        String messageId,
        String eventId,
        String eventType,
        UUID orderId,
        FailureReason failureReason,
        int receiveCount,
        DeadLetterStatus status,
        boolean redrivable,
        UUID redrivenFromId,
        Instant sentAt,
        Instant createdAt,
        Instant updatedAt
) {

    public static DeadLetterSummaryResponse from(DeadLetterListItem item) {
        DeadLetterSummary summary = item.summary();
        return new DeadLetterSummaryResponse(summary.getId(), summary.getMessageId(), summary.getEventId(),
                summary.getEventType(), DeadLetterEvent.orderIdOf(summary.getAggregateType(), summary.getAggregateId()),
                summary.getFailureReason(), summary.getReceiveCount(), summary.getStatus(), item.redrivable(),
                summary.getRedrivenFromId(), summary.getSentAt(), summary.getCreatedAt(), summary.getUpdatedAt());
    }

    public static DeadLetterSummaryResponse from(DeadLetterView view) {
        DeadLetterEvent event = view.event();
        return new DeadLetterSummaryResponse(event.getId(), event.getMessageId(), event.getEventId(),
                event.getEventType(), event.getOrderId(), event.getFailureReason(), event.getReceiveCount(),
                event.getStatus(), view.redrivable(), event.getRedrivenFromId(), event.getSentAt(),
                event.getCreatedAt(), event.getUpdatedAt());
    }
}
