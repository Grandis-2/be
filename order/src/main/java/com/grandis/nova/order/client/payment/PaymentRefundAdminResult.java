package com.grandis.nova.order.client.payment;

import java.time.Instant;
import java.util.UUID;

/**
 * 관리자 환불 조치 결과.
 *
 * @param transactionId 다시 보내기면 새로 연 환불 거래(PENDING), 직접 환불 기록이면 환불 완료로 반영한 거래(SUCCEEDED)
 * @param refundedAt    직접 환불 기록이면 결제사의 취소 시각, 다시 보내기면 null
 */
public record PaymentRefundAdminResult(UUID transactionId, String status, Instant refundedAt) {
}
