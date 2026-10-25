package com.grandis.nova.order.order.api;

import com.grandis.nova.order.client.payment.PaymentRefundIssue;
import com.grandis.nova.order.order.cancel.RefundIssues;
import com.grandis.nova.order.order.domain.model.Order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 멈춘 환불 목록 한 줄. 주문의 사실과 payment 의 판정을 함께 싣는다. 결제 키는 없다.
 *
 * @param orderStatus           주문이 없으면 null
 * @param refundFailedAt        주문에 남긴 환불 확정 실패 시각
 * @param autoRefundRequestedAt 결과를 모른 채 취소한 주문의 늦은 승인을 자동 환불하라고 요청한 시각
 * @param orderUpdatedAt        주문이 마지막으로 바뀐 시각
 * @param reason                payment 의 판정(REFUND_REJECTED · ESCALATED_* · IN_PROGRESS(_LONG) · NO_REFUND_TRANSACTION · NO_PAYMENT),
 *                              payment 가 이미 환불로 끝냈는데 주문이 모르면 REFUNDED_NOT_APPLIED, 판정이 없으면 UNKNOWN
 * @param paymentStatus         SUCCEEDED · REFUNDED, 결제가 없으면 null
 * @param refund                마지막 환불 거래, 없으면 null
 * @param actions               RETRY · MANUAL_COMPLETE — 결제사 조회가 맞지 않으면 조치 때 거절된다
 */
public record AdminRefundIssueResponse(UUID orderId, String orderStatus, String source, BigDecimal totalAmount,
                                       Instant refundFailedAt, Instant autoRefundRequestedAt, Instant orderUpdatedAt,
                                       String reason, String paymentStatus, PaymentRefundIssue.Refund refund,
                                       List<String> actions) {

    static AdminRefundIssueResponse from(RefundIssues.RefundIssue issue) {
        Order order = issue.order();
        PaymentRefundIssue payment = issue.payment();
        return new AdminRefundIssueResponse(issue.orderId(),
                order == null ? null : order.status().name(),
                order == null ? null : order.source().name(),
                order == null ? null : order.totalAmount().amount(),
                order == null ? null : order.refundFailedAt(),
                issue.autoRefundRequestedAt(),
                order == null ? null : order.updatedAt(),
                issue.reason(),
                payment == null ? null : payment.paymentStatus(),
                payment == null ? null : payment.refund(),
                issue.actions());
    }
}
