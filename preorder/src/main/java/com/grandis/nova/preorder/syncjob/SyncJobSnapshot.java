package com.grandis.nova.preorder.syncjob;

import com.grandis.nova.preorder.syncjob.domain.PreorderSyncJob;

import java.time.Instant;

/** 모듈 밖에 내주는 외부 동기화 작업. 작업을 바꾸는 일은 {@link SyncJobs} 와 worker 가 한다. */
public record SyncJobSnapshot(
        Long id,
        Long preorderId,
        SyncJobType jobType,
        SyncJobStatus status,
        String requestPayload,
        Instant leaseExpiresAt,
        Instant deadLetteredAt,
        Instant createdAt,
        Instant updatedAt
) {

    static SyncJobSnapshot of(PreorderSyncJob job) {
        return new SyncJobSnapshot(job.getId(), job.getPreorderId(), job.getJobType(), job.getStatus(),
                job.getRequestPayload(), job.getLeaseExpiresAt(), job.getDeadLetteredAt(), job.getCreatedAt(),
                job.getUpdatedAt());
    }
}
