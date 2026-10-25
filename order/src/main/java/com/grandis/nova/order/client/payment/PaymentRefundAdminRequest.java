package com.grandis.nova.order.client.payment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * 관리자 환불 조치 요청. payment 로 그대로 넘긴다.
 *
 * @param expectedTransactionId 목록에서 본 마지막 환불 거래. 지금의 마지막이 아니면 REFUND_STATE_CHANGED
 * @param note                  왜 조치하는지(500자 이하). 조치 기록에 남는다
 */
public record PaymentRefundAdminRequest(@NotNull UUID expectedTransactionId, @NotBlank @Size(max = 500) String note) {
}
