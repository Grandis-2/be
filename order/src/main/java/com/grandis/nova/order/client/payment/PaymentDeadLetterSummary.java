package com.grandis.nova.order.client.payment;

import java.time.Instant;
import java.util.UUID;

/**
 * payment DeadLetterSummaryResponse 와 같은 모양 — DLQ 목록 한 줄 · 되돌리기 결과. 관리자에게 그대로 내보낸다.
 *
 * 상태 · 분류는 문자열로 받는다 — payment 가 값을 더해도 이 중계가 읽기 실패(500)로 막히지 않게(값은 PaymentDeadLetterStatus ·
 * PaymentDeadLetterFailureReason 과 같다).
 *
 * @param orderId    주문 이벤트면 주문 id, 아니면 null
 * @param redrivable 되돌리기를 기다리고, 저장된 분류 · 종류로 보아 되돌릴 수 있다
 */
public record PaymentDeadLetterSummary(
        UUID deadLetterId,
        String messageId,
        String eventId,
        String eventType,
        UUID orderId,
        String failureReason,
        int receiveCount,
        String status,
        boolean redrivable,
        UUID redrivenFromId,
        Instant sentAt,
        Instant createdAt,
        Instant updatedAt
) {
}
