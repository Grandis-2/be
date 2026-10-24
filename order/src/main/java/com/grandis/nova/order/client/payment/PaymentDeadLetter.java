package com.grandis.nova.order.client.payment;

import java.time.Instant;
import java.util.UUID;

/**
 * payment DeadLetterResponse 와 같은 모양 — DLQ 상세(원문 · 되돌리기 · 버리기 기록). 관리자에게 그대로 내보낸다.
 *
 * 상태 · 분류는 문자열로 받는다(PaymentDeadLetterSummary 와 같은 까닭).
 *
 * @param orderId 주문 이벤트면 주문 id, 아니면 null
 */
public record PaymentDeadLetter(
        UUID deadLetterId,
        String sourceQueue,
        String messageId,
        String eventId,
        String eventType,
        String aggregateType,
        UUID aggregateId,
        UUID orderId,
        String failureReason,
        int receiveCount,
        String status,
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
}
