package com.grandis.nova.preorder.reconciliation.application;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.OffsetPage;
import com.grandis.nova.preorder.PreorderErrorCode;
import com.grandis.nova.preorder.campaign.Campaigns;
import com.grandis.nova.preorder.cancel.AdminCancelService;
import com.grandis.nova.preorder.preorder.PreorderSnapshot;
import com.grandis.nova.preorder.preorder.Preorders;
import com.grandis.nova.preorder.reconciliation.domain.ActionStatus;
import com.grandis.nova.preorder.reconciliation.domain.ActionType;
import com.grandis.nova.preorder.reconciliation.domain.FindingRow;
import com.grandis.nova.preorder.reconciliation.domain.ReconciliationFindings;
import com.grandis.nova.preorder.reconciliation.domain.ReconciliationRunRequest;
import com.grandis.nova.preorder.reconciliation.domain.ReconciliationRunRequestRepository;
import com.grandis.nova.preorder.reconciliation.domain.ReconciliationRuns;
import com.grandis.nova.preorder.reconciliation.domain.RunRow;
import com.grandis.nova.preorder.reconciliation.domain.RunStatus;
import com.grandis.nova.preorder.syncjob.SyncJobReprocessing;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 관리자 정합성 화면. 검사는 batch 가 한다 — 여기서는 실행 요청을 남기고, 결과 표를 읽고, 불일치 건의 조치를 기존 업무 경로
 * (등록 작업 재처리 · 관리자 예약 취소)로 시작하며 그 건의 조치 칸에 남긴다.
 */
@Service
public class ReconciliationAdminService {

    /** 대기 · 실행 중 표식의 UNIQUE. 이것에 걸린 넣기만 409 로 바꾼다 */
    static final String ACTIVE_RUN_CONSTRAINT = "uq_reconciliation_run_active";

    private final ReconciliationRunRequestRepository runRequests;
    private final ReconciliationRuns runs;
    private final ReconciliationFindings findings;
    private final Campaigns campaigns;
    private final Preorders preorders;
    private final SyncJobReprocessing reprocessing;
    private final AdminCancelService adminCancel;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final Duration intakeRushWindow;

    ReconciliationAdminService(ReconciliationRunRequestRepository runRequests, ReconciliationRuns runs,
                               ReconciliationFindings findings, Campaigns campaigns, Preorders preorders,
                               SyncJobReprocessing reprocessing, AdminCancelService adminCancel,
                               TransactionTemplate transactionTemplate, Clock clock,
                               @Value("${nova.reconciliation.intake-rush-window:1h}") Duration intakeRushWindow) {
        this.runRequests = runRequests;
        this.runs = runs;
        this.findings = findings;
        this.campaigns = campaigns;
        this.preorders = preorders;
        this.reprocessing = reprocessing;
        this.adminCancel = adminCancel;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
        this.intakeRushWindow = intakeRushWindow;
    }

    /**
     * 실행 요청을 남긴다. batch 가 몇 초 안에 가져가 검사한다.
     *
     * @param productId 검사 범위. null 이면 전체
     * @throws BusinessException PRODUCT_NOT_FOUND(회차가 없는 상품) · RECONCILIATION_INTAKE_RUSH(오픈 후 1시간 안 회차가 있음) ·
     *                           RECONCILIATION_RUN_ACTIVE(대기 · 실행 중인 실행이 있음)
     */
    @Transactional
    public RunView requestRun(UUID productId, String requestedBy) {
        if (productId != null && !campaigns.exists(productId)) {
            throw new BusinessException(PreorderErrorCode.PRODUCT_NOT_FOUND);
        }
        if (campaigns.anyOpenedWithin(clock.instant(), intakeRushWindow)) {
            throw new BusinessException(PreorderErrorCode.RECONCILIATION_INTAKE_RUSH);
        }
        UUID runId;
        try {
            runId = runRequests.saveAndFlush(ReconciliationRunRequest.of(requestedBy, productId)).getId();
        } catch (DataIntegrityViolationException e) {
            if (!String.valueOf(e.getMostSpecificCause().getMessage()).contains(ACTIVE_RUN_CONSTRAINT)) {
                throw e;
            }
            throw new BusinessException(PreorderErrorCode.RECONCILIATION_RUN_ACTIVE);
        }
        return new RunView(runs.find(runId).orElseThrow(), Map.of());
    }

    /** 최근 실행부터. 실행마다 코드별 불일치 건수를 함께 준다 */
    @Transactional(readOnly = true)
    public OffsetPage<RunView> findRuns(int page, int size) {
        OffsetPage<RunRow> found = runs.findPage(page, size);
        Map<UUID, Map<String, Long>> counts = runs.countFindings(found.items().stream().map(RunRow::id).toList());
        return found.map(run -> new RunView(run, counts.getOrDefault(run.id(), Map.of())));
    }

    /**
     * @param checkCode 이 코드만. null 이면 전부
     * @throws BusinessException RECONCILIATION_RUN_NOT_FOUND
     */
    @Transactional(readOnly = true)
    public OffsetPage<FindingRow> findFindings(UUID runId, String checkCode, int page, int size) {
        requireRun(runId);
        return findings.findPage(runId, checkCode, page, size);
    }

    /**
     * 두 실행의 불일치를 (코드, 대상)으로 맞대어 신규(run 에만) · 잔여(둘 다) · 해소(against 에만)로 나눈다.
     * 범위가 다르면 검사하지 않은 건이 해소로 보이고, 끝나지 않은 실행은 결과가 없어 모두 해소로 보이므로 비교하지 않는다.
     *
     * @param againstRunId 비교 대상(보통 이전 실행)
     * @throws BusinessException RECONCILIATION_RUN_NOT_FOUND · RECONCILIATION_NOT_COMPARABLE
     */
    @Transactional(readOnly = true)
    public RunDiff diff(UUID runId, UUID againstRunId) {
        RunRow run = requireRun(runId);
        RunRow against = requireRun(againstRunId);
        if (run.status() != RunStatus.COMPLETED || against.status() != RunStatus.COMPLETED) {
            throw new BusinessException(PreorderErrorCode.RECONCILIATION_NOT_COMPARABLE,
                    Map.of("reason", "status=" + run.status() + ", againstStatus=" + against.status()));
        }
        if (!Objects.equals(run.productId(), against.productId())) {
            throw new BusinessException(PreorderErrorCode.RECONCILIATION_NOT_COMPARABLE,
                    Map.of("reason", "productId=" + run.productId() + ", againstProductId=" + against.productId()));
        }
        return new RunDiff(runId, againstRunId, findings.findOnlyIn(runId, againstRunId),
                findings.findInBoth(runId, againstRunId), findings.findOnlyIn(againstRunId, runId));
    }

    /**
     * 그 건의 예약의 등록 작업을 재처리하고 조치를 남긴다. 둘이 한 트랜잭션이다 — 재처리가 거절되면 조치도 남지 않는다.
     * 건을 먼저 잠가 같은 건의 동시 조치는 뒤의 것이 "이미 조치함" 으로 끝난다.
     *
     * @throws BusinessException RECONCILIATION_FINDING_NOT_FOUND · RECONCILIATION_ACTION_NOT_ALLOWED ·
     *                           RECONCILIATION_ACTION_TAKEN · PREORDER_NOT_FOUND · SYNC_JOB_NOT_REPROCESSABLE
     */
    @Transactional
    public FindingRow reprocess(UUID findingId, String requestedBy) {
        FindingRow finding = findings.findForUpdate(findingId)
                .orElseThrow(() -> new BusinessException(PreorderErrorCode.RECONCILIATION_FINDING_NOT_FOUND));
        requireActionable(finding, ActionType.REPROCESS);
        PreorderSnapshot preorder = preorders.findByToken(finding.targetKey())
                .orElseThrow(() -> new BusinessException(PreorderErrorCode.PREORDER_NOT_FOUND));
        reprocessing.reprocessRegister(preorder.id(), requestedBy);
        if (findings.markRequested(findingId, ActionType.REPROCESS, requestedBy, clock.instant()) != 1) {
            throw new IllegalStateException("잠근 불일치 건의 조치 상태가 바뀌었다: findingId=" + findingId);
        }
        return findings.find(findingId).orElseThrow();
    }

    /**
     * 그 건의 예약을 관리자 취소하고 조치를 남긴다. 취소는 주문 사전 확인(order 호출)이 있어 트랜잭션 밖에서 먼저 하고, 조치는 뒤에
     * 짧은 트랜잭션으로 남긴다. 취소는 이미 취소 중이면 그대로 두므로 같은 건에 두 번 들어와도 취소는 한 번이다.
     *
     * @param reason 이력에 남는 취소 사유
     * @throws BusinessException RECONCILIATION_FINDING_NOT_FOUND · RECONCILIATION_ACTION_NOT_ALLOWED ·
     *                           RECONCILIATION_ACTION_TAKEN · PREORDER_NOT_FOUND · PREORDER_NOT_CANCELABLE
     */
    public FindingRow cancel(UUID findingId, String reason, String requestedBy) {
        FindingRow finding = findings.find(findingId)
                .orElseThrow(() -> new BusinessException(PreorderErrorCode.RECONCILIATION_FINDING_NOT_FOUND));
        requireActionable(finding, ActionType.ADMIN_CANCEL);
        adminCancel.cancel(finding.targetKey(), reason);
        return transactionTemplate.execute(status -> {
            if (findings.markRequested(findingId, ActionType.ADMIN_CANCEL, requestedBy, clock.instant()) != 1) {
                throw actionTaken(findings.find(findingId).orElseThrow());
            }
            return findings.find(findingId).orElseThrow();
        });
    }

    private RunRow requireRun(UUID runId) {
        return runs.find(runId)
                .orElseThrow(() -> new BusinessException(PreorderErrorCode.RECONCILIATION_RUN_NOT_FOUND));
    }

    private static void requireActionable(FindingRow finding, ActionType actionType) {
        if (!actionType.allows(finding.checkCode())) {
            throw new BusinessException(PreorderErrorCode.RECONCILIATION_ACTION_NOT_ALLOWED,
                    Map.of("reason", "checkCode=" + finding.checkCode() + ", action=" + actionType));
        }
        if (finding.actionStatus() != ActionStatus.NOT_STARTED) {
            throw actionTaken(finding);
        }
    }

    private static BusinessException actionTaken(FindingRow finding) {
        return new BusinessException(PreorderErrorCode.RECONCILIATION_ACTION_TAKEN,
                Map.of("reason", "actionStatus=" + finding.actionStatus() + ", actionType=" + finding.actionType()));
    }
}
