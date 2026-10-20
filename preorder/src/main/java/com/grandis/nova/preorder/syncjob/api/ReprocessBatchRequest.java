package com.grandis.nova.preorder.syncjob.api;

import com.grandis.nova.preorder.syncjob.SyncJobType;
import com.grandis.nova.preorder.syncjob.application.SyncJobAdminService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * @param syncJobIds 비우면 DEAD_LETTER 인 jobType 작업 전체
 * @param jobType    syncJobIds 를 비웠을 때 고를 작업 종류. 없으면 REGISTER(이 칸이 생기기 전과 같다)
 */
record ReprocessBatchRequest(
        @Size(max = SyncJobAdminService.MAX_BATCH_SIZE) List<@NotNull UUID> syncJobIds,
        String errorCodeFilter,
        @Min(1) @Max(200) Integer ratePerSecond,
        SyncJobType jobType
) {

    static final int DEFAULT_RATE_PER_SECOND = 20;

    public SyncJobType jobTypeOrDefault() {
        return jobType == null ? SyncJobType.REGISTER : jobType;
    }

    public int rateOrDefault() {
        return ratePerSecond == null ? DEFAULT_RATE_PER_SECOND : ratePerSecond;
    }
}
