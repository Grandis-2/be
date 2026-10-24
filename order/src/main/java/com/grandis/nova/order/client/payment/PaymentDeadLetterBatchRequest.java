package com.grandis.nova.order.client.payment;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * 결제 DLQ 일괄 되돌리기. 관리자 API 가 받은 그대로 payment RedriveBatchRequest 로 넘긴다 — 범위는 payment 와 같고 여기서 먼저 가른다.
 *
 * @param deadLetterIds 비우면 조건(eventType · failureReason)에 맞는 되돌리기 대기 행을 쌓인 순으로
 * @param failureReason 조건으로 고를 때 비우면 PROCESSING_FAILED
 * @param ratePerSecond 비우면 20
 */
public record PaymentDeadLetterBatchRequest(
        @Size(max = 1000) List<@NotNull UUID> deadLetterIds,
        String eventType,
        PaymentDeadLetterFailureReason failureReason,
        @Min(1) @Max(200) Integer ratePerSecond
) {
}
