package com.grandis.nova.preorder.syncjob.application;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.outbox.OutboxWriter;
import com.grandis.nova.preorder.PreorderErrorCode;
import com.grandis.nova.preorder.preorder.PreorderLedger;
import com.grandis.nova.preorder.preorder.PreorderStatus;
import com.grandis.nova.preorder.preorder.Preorders;
import com.grandis.nova.preorder.syncjob.RegisterJobReady;
import com.grandis.nova.preorder.syncjob.SyncActor;
import com.grandis.nova.preorder.syncjob.domain.PreorderSyncJob;
import com.grandis.nova.preorder.syncjob.domain.PreorderSyncJobRepository;
import com.grandis.nova.preorder.syncjob.domain.ReprocessCandidate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * DEAD_LETTER 인 REGISTER 작업을 PENDING 으로 되돌리고 등록 메시지를 다시 보낸다. 되돌린 작업은 다시 요청해도 거절된다.
 * 취소 중 · 취소된 예약은 되살리지 않는다. 취소 경로가 먼저 잠그는 예약 행을 잠가 판정과 기록을 취소와 직렬화한다.
 */
@Component
class SyncJobReprocessor {

    private static final Logger log = LoggerFactory.getLogger(SyncJobReprocessor.class);

    private final PreorderSyncJobRepository syncJobs;
    private final PreorderLedger ledger;
    private final Preorders preorders;
    private final OutboxWriter outboxWriter;
    private final Clock clock;

    SyncJobReprocessor(PreorderSyncJobRepository syncJobs, PreorderLedger ledger, Preorders preorders,
                       OutboxWriter outboxWriter, Clock clock) {
        this.syncJobs = syncJobs;
        this.ledger = ledger;
        this.preorders = preorders;
        this.outboxWriter = outboxWriter;
        this.clock = clock;
    }

    /** @return 되돌린 뒤의 작업 */
    @Transactional
    public PreorderSyncJob reprocess(UUID syncJobId, String requestedBy) {
        UUID preorderId = syncJobs.findPreorderId(syncJobId)
                .orElseThrow(() -> new BusinessException(PreorderErrorCode.SYNC_JOB_NOT_FOUND));
        PreorderStatus preorderStatus = ledger.lockStatus(preorderId);
        // 잠근 뒤 처음 읽어야 그 사이 취소가 바꾼 작업 상태를 본다
        PreorderSyncJob job = syncJobs.findById(syncJobId).orElseThrow();
        Optional<String> blocker = new ReprocessCandidate(job.getId(), job.getJobType(), job.getStatus(),
                preorderStatus, null).blocker();
        if (blocker.isPresent() || syncJobs.reopen(syncJobId, clock.instant()) == 0) {
            throw new BusinessException(PreorderErrorCode.SYNC_JOB_NOT_REPROCESSABLE,
                    Map.of("reason", blocker.orElse("status=" + job.getStatus())));
        }
        outboxWriter.append(new RegisterJobReady(syncJobId, preorders.getById(preorderId).preorderToken(),
                job.getRequestPayload(), SyncActor.ADMIN));
        log.info("외부 등록 작업을 재처리한다 syncJobId={} requestedBy={}", syncJobId, requestedBy);
        return syncJobs.findById(syncJobId).orElseThrow();
    }
}
