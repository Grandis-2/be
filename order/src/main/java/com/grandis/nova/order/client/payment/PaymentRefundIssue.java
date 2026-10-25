package com.grandis.nova.order.client.payment;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * payment 가 판정한 한 주문의 환불 상태(GET /internal/refund-issues). 값은 문자열로 받는다 — payment 가 새 사유 · 조치를 더해도 order 가
 * 깨지지 않고 관리자에게 그대로 보인다.
 *
 * @param reason        REFUND_REJECTED · ESCALATED_TIMED_OUT · ESCALATED_NOT_FOUND · ESCALATED_UNEXPECTED_STATE · ESCALATED_MISMATCH ·
 *                      IN_PROGRESS · IN_PROGRESS_LONG · NO_REFUND_TRANSACTION · NO_PAYMENT · REFUNDED
 * @param paymentStatus SUCCEEDED · REFUNDED, 성공한 결제가 없으면 null
 * @param refund        마지막 환불 거래, 없으면 null
 * @param actions       RETRY · MANUAL_COMPLETE
 */
public record PaymentRefundIssue(UUID orderId, String reason, String paymentStatus, Refund refund, List<String> actions) {

    public record Refund(UUID transactionId, String status, int attemptCount, Instant requestedAt, Instant finishedAt,
                         Instant escalatedAt, String lastErrorCode, String lastErrorMessage, Instant createdAt) {
    }
}
