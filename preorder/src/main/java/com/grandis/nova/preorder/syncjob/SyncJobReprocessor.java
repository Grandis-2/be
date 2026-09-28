package com.grandis.nova.preorder.syncjob;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.preorder.PreorderErrorCode;
import com.grandis.nova.preorder.outbox.OutboxWriter;
import com.grandis.nova.preorder.preorder.PreorderLedger;
import com.grandis.nova.preorder.preorder.PreorderStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;

/**
 * DEAD_LETTER 인 REGISTER 작업의 재처리 요청을 남긴다. 작업을 되돌리는 것은 worker 다(조건부라 여러 번 와도 한 번).
 * 취소 중 · 취소된 예약은 되살리지 않는다. 취소 경로가 먼저 잠그는 예약 행을 잠가 판정과 기록을 취소와 직렬화한다.
 */
@Component
class SyncJobReprocessor {

    private final PreorderSyncJobRepository syncJobs;
    private final PreorderLedger ledger;
    private final OutboxWriter outboxWriter;

    SyncJobReprocessor(PreorderSyncJobRepository syncJobs, PreorderLedger ledger,
                              OutboxWriter outboxWriter) {
        this.syncJobs = syncJobs;
        this.ledger = ledger;
        this.outboxWriter = outboxWriter;
    }

    /** @return 요청 시점의 작업 */
    @Transactional
    public PreorderSyncJob reprocess(Long syncJobId, String requestedBy) {
        Long preorderId = syncJobs.findPreorderId(syncJobId)
                .orElseThrow(() -> new BusinessException(PreorderErrorCode.SYNC_JOB_NOT_FOUND));
        PreorderStatus preorderStatus = ledger.lockStatus(preorderId);
        // 잠근 뒤 처음 읽어야 그 사이 취소가 바꾼 작업 상태를 본다
        PreorderSyncJob job = syncJobs.findById(syncJobId).orElseThrow();
        Optional<String> blocker = blocker(job.getJobType(), job.getStatus(), preorderStatus);
        if (blocker.isPresent()) {
            throw new BusinessException(PreorderErrorCode.SYNC_JOB_NOT_REPROCESSABLE,
                    Map.of("reason", blocker.get()));
        }
        outboxWriter.append(new SyncJobReprocessRequested(job.getId(), requestedBy));
        return job;
    }

    /** 재처리할 수 없는 까닭. 할 수 있으면 비어 있다. */
    static Optional<String> blocker(SyncJobType jobType, SyncJobStatus status, PreorderStatus preorderStatus) {
        if (jobType != SyncJobType.REGISTER || status != SyncJobStatus.DEAD_LETTER) {
            return Optional.of("jobType=%s, status=%s".formatted(jobType, status));
        }
        if (preorderStatus == PreorderStatus.CANCELING || preorderStatus == PreorderStatus.CANCELED) {
            return Optional.of("preorderStatus=" + preorderStatus);
        }
        return Optional.empty();
    }
}
