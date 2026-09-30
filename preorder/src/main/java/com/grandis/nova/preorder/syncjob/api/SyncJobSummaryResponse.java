package com.grandis.nova.preorder.syncjob.api;


import com.grandis.nova.preorder.syncjob.SyncJobStatus;
import com.grandis.nova.preorder.syncjob.SyncJobType;
import com.grandis.nova.preorder.syncjob.application.SyncJobView;
import com.grandis.nova.preorder.syncjob.domain.PreorderSyncJob;

import java.time.Instant;

record SyncJobSummaryResponse(
        Long syncJobId,
        String preorderId,
        SyncJobType jobType,
        SyncJobStatus status,
        int attemptCount,
        String lastErrorCode,
        Instant leaseExpiresAt,
        Instant deadLetteredAt,
        Instant createdAt,
        Instant updatedAt
) {

    public static SyncJobSummaryResponse from(SyncJobView view) {
        PreorderSyncJob job = view.job();
        return new SyncJobSummaryResponse(job.getId(), view.preorderToken(), job.getJobType(), job.getStatus(),
                view.attempts().size(), view.lastErrorCode(), job.getLeaseExpiresAt(), job.getDeadLetteredAt(),
                job.getCreatedAt(), job.getUpdatedAt());
    }
}
