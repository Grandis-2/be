package com.grandis.nova.preorder.syncjob;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** @param syncJobIds 비우면 DEAD_LETTER 인 REGISTER 전체 */
public record ReprocessBatchRequest(
        @Size(max = SyncJobAdminService.MAX_BATCH_SIZE) List<@NotNull Long> syncJobIds,
        String errorCodeFilter,
        @Min(1) @Max(200) Integer ratePerSecond
) {

    static final int DEFAULT_RATE_PER_SECOND = 20;

    public int rateOrDefault() {
        return ratePerSecond == null ? DEFAULT_RATE_PER_SECOND : ratePerSecond;
    }
}
