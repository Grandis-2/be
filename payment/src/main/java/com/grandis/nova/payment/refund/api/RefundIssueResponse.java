package com.grandis.nova.payment.refund.api;

import com.grandis.nova.payment.domain.model.PaymentTransaction;
import com.grandis.nova.payment.refund.RefundIssue;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 한 주문의 환불 판정. 결제 키는 싣지 않는다.
 *
 * @param paymentStatus 성공한 결제가 없으면 null(SUCCEEDED · REFUNDED)
 * @param refund        마지막 환불 거래. 없으면 null
 */
public record RefundIssueResponse(UUID orderId, String reason, String paymentStatus, Refund refund, List<String> actions) {

    /** 마지막 환불 거래. lastErrorCode 는 결제사 거절 코드이거나 복구가 멈춘 사유 코드다. */
    public record Refund(UUID transactionId, String status, int attemptCount, Instant requestedAt, Instant finishedAt,
                         Instant escalatedAt, String lastErrorCode, String lastErrorMessage, Instant createdAt) {

        static Refund from(PaymentTransaction transaction) {
            return new Refund(transaction.id(), transaction.status().name(), transaction.attemptCount(),
                    transaction.requestedAt(), transaction.finishedAt(), transaction.escalatedAt(),
                    transaction.lastError() == null ? null : transaction.lastError().code(),
                    transaction.lastError() == null ? null : transaction.lastError().message(), transaction.createdAt());
        }
    }

    public static RefundIssueResponse from(RefundIssue issue) {
        return new RefundIssueResponse(issue.orderId(), issue.reason().name(),
                issue.paymentStatus() == null ? null : issue.paymentStatus().name(),
                issue.lastRefund() == null ? null : Refund.from(issue.lastRefund()),
                issue.actions().stream().map(Enum::name).toList());
    }
}
