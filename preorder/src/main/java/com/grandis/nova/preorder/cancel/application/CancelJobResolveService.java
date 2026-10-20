package com.grandis.nova.preorder.cancel.application;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.preorder.PreorderErrorCode;
import com.grandis.nova.preorder.cancel.CancelCompletion;
import com.grandis.nova.preorder.preorder.EventActor;
import com.grandis.nova.preorder.preorder.PreorderLedger;
import com.grandis.nova.preorder.preorder.PreorderSnapshot;
import com.grandis.nova.preorder.preorder.PreorderStatus;
import com.grandis.nova.preorder.preorder.Preorders;
import com.grandis.nova.preorder.syncjob.SyncJobSnapshot;
import com.grandis.nova.preorder.syncjob.SyncJobStatus;
import com.grandis.nova.preorder.syncjob.SyncJobType;
import com.grandis.nova.preorder.syncjob.SyncJobs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;

/**
 * 포기한 외부 취소를 관리자가 끝낸다. 외부에서 그 예약이 취소됐음(또는 없음)을 확인한 뒤에 쓴다 — 작업은 SUCCEEDED, 예약은 CANCELED,
 * 회원에게 취소 알림. worker 의 외부 취소 성공과 같은 결과이고 이력에는 관리자와 사유가 남는다.
 * 예약 행을 먼저 잠가 재처리 · 늦게 온 worker 결과와 줄 세운다.
 */
@Service
public class CancelJobResolveService {

    private static final Logger log = LoggerFactory.getLogger(CancelJobResolveService.class);

    private final SyncJobs syncJobs;
    private final PreorderLedger ledger;
    private final Preorders preorders;
    private final CancelCompletion cancelCompletion;
    private final Clock clock;

    CancelJobResolveService(SyncJobs syncJobs, PreorderLedger ledger, Preorders preorders,
                            CancelCompletion cancelCompletion, Clock clock) {
        this.syncJobs = syncJobs;
        this.ledger = ledger;
        this.preorders = preorders;
        this.cancelCompletion = cancelCompletion;
        this.clock = clock;
    }

    /**
     * @throws BusinessException 404 SYNC_JOB_NOT_FOUND. 409 SYNC_JOB_NOT_RESOLVABLE — 취소 작업이 아니거나, 포기한 작업이 아니거나
     *                           (진행 중 · 이미 끝남), 예약이 취소 중이 아니다
     */
    @Transactional
    public CancelResult resolve(UUID syncJobId, String reason, String requestedBy) {
        UUID preorderId = syncJobs.findPreorderId(syncJobId)
                .orElseThrow(() -> new BusinessException(PreorderErrorCode.SYNC_JOB_NOT_FOUND));
        PreorderStatus preorderStatus = ledger.lockStatus(preorderId);
        // 잠근 뒤 처음 읽어야 그 사이 재처리 · 늦은 성공이 바꾼 작업 상태를 본다
        SyncJobSnapshot job = syncJobs.findById(syncJobId).orElseThrow();
        if (job.jobType() != SyncJobType.CANCEL || job.status() != SyncJobStatus.DEAD_LETTER
                || preorderStatus != PreorderStatus.CANCELING) {
            throw new BusinessException(PreorderErrorCode.SYNC_JOB_NOT_RESOLVABLE, Map.of("reason",
                    "jobType=%s, status=%s, preorderStatus=%s".formatted(job.jobType(), job.status(), preorderStatus)));
        }
        if (syncJobs.resolve(syncJobId, clock.instant()) != 1
                || !cancelCompletion.complete(preorderId, EventActor.ADMIN, reason)) {
            throw new IllegalStateException("잠근 뒤 확인한 취소를 끝내지 못했다: syncJobId=" + syncJobId);
        }
        log.info("포기한 외부 취소를 관리자가 끝냈다 syncJobId={} preorderId={} requestedBy={}", syncJobId, preorderId,
                requestedBy);
        PreorderSnapshot preorder = preorders.getById(preorderId);
        return new CancelResult(preorder.preorderToken(), preorder.status(), preorder.eventSequence());
    }
}
