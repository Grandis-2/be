package com.grandis.nova.order.client.payment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 결제 DLQ 버리기. 관리자 API 가 받은 그대로 payment DiscardRequest 로 넘긴다. @param note 버리는 까닭. payment 에 기록으로 남는다 */
public record PaymentDeadLetterDiscardRequest(@NotBlank @Size(max = 500) String note) {
}
