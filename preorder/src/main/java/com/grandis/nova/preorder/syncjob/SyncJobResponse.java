package com.grandis.nova.preorder.syncjob;

import com.fasterxml.jackson.annotation.JsonRawValue;

import java.time.Instant;
import java.util.List;

/**
 * 외부 동기화 작업(openapi SyncJobDetail). 누적 시도 수와 마지막 오류는 시도 기록에서 센다 —
 * 작업 행에 세어 두면 워커가 죽었을 때 둘이 어긋난다.
 */
public record SyncJobResponse(
        Long syncJobId,
        String preorderId,
        SyncJobType jobType,
        SyncJobStatus status,
        int attemptCount,
        String lastErrorCode,
        Instant leaseExpiresAt,
        Instant deadLetteredAt,
        Instant createdAt,
        Instant updatedAt,
        @JsonRawValue String requestPayload,
        List<SyncAttemptResponse> attempts
) {

    public static SyncJobResponse from(PreorderSyncJob job, String preorderToken, List<SyncAttempt> attempts) {
        List<SyncAttemptResponse> responses = attempts.stream().map(SyncAttemptResponse::from).toList();
        return new SyncJobResponse(job.getId(), preorderToken, job.getJobType(), job.getStatus(),
                responses.size(), lastErrorCode(attempts), job.getLeaseExpiresAt(), job.getDeadLetteredAt(),
                job.getCreatedAt(), job.getUpdatedAt(), job.getRequestPayload(), responses);
    }

    private static String lastErrorCode(List<SyncAttempt> attempts) {
        return attempts.isEmpty() ? null : attempts.getLast().errorCode();
    }
}
