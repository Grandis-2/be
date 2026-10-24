package com.grandis.nova.payment.deadletter.api;

import com.grandis.nova.payment.deadletter.application.DeadLetterView;
import com.grandis.nova.payment.deadletter.domain.DeadLetterEvent;
import com.grandis.nova.payment.deadletter.domain.DeadLetterStatus;
import com.grandis.nova.payment.deadletter.domain.FailureReason;

import java.time.Instant;
import java.util.UUID;

/**
 * 상세. 원문과 되돌리기 · 버리기 기록을 함께 준다. 실제 예외는 messageId 로 처리 실패 로그에서 찾는다.
 *
 * @param orderId 주문 이벤트(aggregate ORDER)면 주문 id, 아니면 null
 */
record DeadLetterResponse(
        UUID deadLetterId,
        String sourceQueue,
        String messageId,
        String eventId,
        String eventType,
        String aggregateType,
        UUID aggregateId,
        UUID orderId,
        FailureReason failureReason,
        int receiveCount,
        DeadLetterStatus status,
        boolean redrivable,
        UUID redrivenFromId,
        String body,
        Instant sentAt,
        String redriveRequestedBy,
        Instant redriveStartedAt,
        Instant redrivenAt,
        Instant outcomeAt,
        String discardedBy,
        Instant discardedAt,
        String discardNote,
        Instant createdAt,
        Instant updatedAt
) {

    public static DeadLetterResponse from(DeadLetterView view) {
        DeadLetterEvent event = view.event();
        return new DeadLetterResponse(event.getId(), event.getSourceQueue(), event.getMessageId(), event.getEventId(),
                event.getEventType(), event.getAggregateType(), event.getAggregateId(), event.getOrderId(),
                event.getFailureReason(), event.getReceiveCount(), event.getStatus(), view.redrivable(),
                event.getRedrivenFromId(), event.getBody(), event.getSentAt(), event.getRedriveRequestedBy(),
                event.getRedriveStartedAt(), event.getRedrivenAt(), event.getOutcomeAt(), event.getDiscardedBy(),
                event.getDiscardedAt(), event.getDiscardNote(), event.getCreatedAt(), event.getUpdatedAt());
    }
}
