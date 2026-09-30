package com.grandis.nova.preorder.deadletter;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * @param deadLetterIds 비우면 조건(eventType · failureReason)에 맞는 되돌리기 대기 행을 id 순으로
 * @param failureReason 조건으로 고를 때 비우면 PROCESSING_FAILED
 */
record RedriveBatchRequest(
        @Size(max = DeadLetterAdminService.MAX_BATCH_SIZE) List<@NotNull Long> deadLetterIds,
        String eventType,
        FailureReason failureReason,
        @Min(1) @Max(200) Integer ratePerSecond
) {

    static final int DEFAULT_RATE_PER_SECOND = 20;

    public int rateOrDefault() {
        return ratePerSecond == null ? DEFAULT_RATE_PER_SECOND : ratePerSecond;
    }
}
