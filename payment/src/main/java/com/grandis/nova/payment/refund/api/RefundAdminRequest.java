package com.grandis.nova.payment.refund.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * @param expectedTransactionId 관리자가 목록에서 본 마지막 환불. 지금의 마지막이 아니면 REFUND_STATE_CHANGED
 * @param note                  왜 조치하는지. 조치 기록에 남는다
 */
public record RefundAdminRequest(@NotNull UUID expectedTransactionId, @NotBlank @Size(max = 500) String note) {
}
