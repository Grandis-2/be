package com.grandis.nova.preorder.deadletter;

import java.time.Instant;

/**
 * 상세. 원문과 되돌리기 · 버리기 기록을 함께 준다. 실제 예외는 messageId 로 처리 실패 로그에서 찾는다.
 *
 * @param preorderId 예약 공개 UUID. 예약이 없는 이벤트면 null
 */
record DeadLetterResponse(
        Long deadLetterId,
        String sourceQueue,
        String messageId,
        String eventId,
        String eventType,
        String aggregateType,
        Long aggregateId,
        String preorderId,
        Long customerId,
        FailureReason failureReason,
        int receiveCount,
        DeadLetterStatus status,
        boolean redrivable,
        Long redrivenFromId,
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
                event.getEventType(), event.getAggregateType(), event.getAggregateId(), view.preorderToken(),
                event.getCustomerId(), event.getFailureReason(), event.getReceiveCount(), event.getStatus(),
                view.redrivable(), event.getRedrivenFromId(), event.getBody(), event.getSentAt(),
                event.getRedriveRequestedBy(), event.getRedriveStartedAt(), event.getRedrivenAt(), event.getOutcomeAt(),
                event.getDiscardedBy(), event.getDiscardedAt(), event.getDiscardNote(), event.getCreatedAt(),
                event.getUpdatedAt());
    }
}
