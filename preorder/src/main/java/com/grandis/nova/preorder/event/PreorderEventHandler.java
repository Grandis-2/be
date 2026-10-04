package com.grandis.nova.preorder.event;

import com.grandis.nova.common.outbox.OutboxWriter;
import com.grandis.nova.preorder.cancel.CancelRequestPayload;
import com.grandis.nova.preorder.preorder.EventActor;
import com.grandis.nova.preorder.preorder.PreorderFact;
import com.grandis.nova.preorder.preorder.PreorderHistoryEntry;
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
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.Optional;

/**
 * preorder 가 받는 이벤트의 처리. 메시지 하나 = 트랜잭션 하나다.
 *
 * 같은 메시지를 두 번 받아도 결과가 같아야 한다(SQS 는 at-least-once). 상태 머신이 지금 상태에서 의미 없는 사건을
 * 무시하고, 작업은 예약 행을 잠근 채 있는지 먼저 본다. 순서도 보장되지 않으므로 판단은 원장으로 한다.
 */
@Component
public class PreorderEventHandler {

    private static final Logger log = LoggerFactory.getLogger(PreorderEventHandler.class);

    private static final String REJECTED_BY_ORDER = "ORDER_REJECTED";

    /** 취소를 시작한 주체 → Mock 감사 사유. 만료는 SYSTEM 이 시작한다. */
    private static final Map<EventActor, String> MOCK_CANCEL_REASONS = Map.of(
            EventActor.USER, "USER_CANCEL",
            EventActor.ADMIN, "ADMIN_CANCEL",
            EventActor.SYSTEM, "DEADLINE_EXCEEDED");

    private final Preorders preorders;
    private final SyncJobs syncJobs;
    private final PreorderLedger ledger;
    private final OutboxWriter outboxWriter;
    private final JsonMapper jsonMapper;

    public PreorderEventHandler(Preorders preorders,
                                SyncJobs syncJobs, PreorderLedger ledger, OutboxWriter outboxWriter,
                                JsonMapper jsonMapper) {
        this.preorders = preorders;
        this.syncJobs = syncJobs;
        this.ledger = ledger;
        this.outboxWriter = outboxWriter;
        this.jsonMapper = jsonMapper;
    }

    /**
     * 외부 등록 · 취소 성공. 작업 원장이 SUCCEEDED 인지 다시 본 뒤에만 반영한다.
     * 등록 성공이 취소 중인 예약에 늦게 오면 상태 머신이 무시한다 — 외부 쪽은 CANCEL 작업이 정리한다.
     */
    @Transactional
    public void onExternalJobSucceeded(ExternalJobSucceeded message) {
        Optional<SyncJobSnapshot> job = syncJobs.findById(message.syncJobId())
                .filter(found -> found.status() == SyncJobStatus.SUCCEEDED);
        if (job.isEmpty()) {
            log.warn("성공하지 않은 작업의 성공 이벤트를 무시한다 syncJobId={}", message.syncJobId());
            return;
        }
        Long preorderId = job.get().preorderId();
        if (job.get().jobType() == SyncJobType.REGISTER) {
            ledger.fire(preorderId, new PreorderFact.RegisterConfirmed(message.externalNumber()));
        } else {
            ledger.fire(preorderId, new PreorderFact.CancelCompleted());
        }
    }

    /** 결제 시작. 화면의 "결제 진행 중" 표시용 시각만 남긴다 — 결제 가능 상태가 아니면 무시된다. */
    @Transactional
    public void onPaymentStarted(PreorderPaymentStarted message) {
        ledger.fire(requirePreorder(message.preorderId()).id(),
                new PreorderFact.PaymentStarted(message.startedAt()));
    }

    /** 결제 확인 → 예약 확정. 취소 중이면 시각만 남겨 거절됐을 때 확정으로 돌아갈 근거로 둔다. */
    @Transactional
    public void onPaymentConfirmed(PreorderPaymentConfirmed message) {
        ledger.fire(requirePreorder(message.preorderId()).id(), new PreorderFact.PaymentConfirmed(message.paidAt()));
    }

    /**
     * 주문 정리 결과. 주문이 없거나 취소됐으면 외부 취소 작업을 만들고, 거절이면 PAYABLE · RESERVED 로 되돌린다.
     * 외부 취소는 주문 정리가 끝난 뒤에만 한다 — 주문이 거절되면(배송 시작) 외부 취소를 되돌릴 수 없다.
     *
     * 한 예약이 취소 → 거절 → 다시 취소를 거칠 수 있으므로, 지금 취소 시도의 결과일 때만 반영한다.
     * 이전 시도의 결과가 다시 · 늦게 오면 무시한다.
     */
    @Transactional
    public void onOrderSettled(PreorderOrderSettled message) {
        PreorderSnapshot preorder = requirePreorder(message.preorderId());
        if (!isCurrentCancel(preorder.id(), message.cancelSequence())) {
            log.warn("지금 취소 시도의 결과가 아니라 무시한다 preorderId={} cancelSequence={}",
                    message.preorderId(), message.cancelSequence());
            return;
        }
        switch (message.result()) {
            case NO_ORDER, CANCELED -> requestExternalCancel(preorder);
            case REJECTED -> ledger.fire(preorder.id(),
                    new PreorderFact.CancelRejected(rejectionReason(message.reason()), message.paidAt()));
        }
    }

    private PreorderSnapshot requirePreorder(String preorderToken) {
        return preorders.findByToken(preorderToken)
                .orElseThrow(() -> new IllegalArgumentException("예약이 없다: " + preorderToken));
    }

    /**
     * 예약 행을 잠근 채 취소 중인지, 결과의 시도 순번이 마지막 CANCELING 진입 이력과 같은지 본다.
     * 잠금은 이 트랜잭션 끝까지 유지되므로 뒤이은 판단 사이에 새 취소가 끼어들지 못한다.
     */
    private boolean isCurrentCancel(Long preorderId, Long cancelSequence) {
        PreorderStatus status = ledger.lockStatus(preorderId);
        return status == PreorderStatus.CANCELING
                && preorders.lastTransitionTo(preorderId, PreorderStatus.CANCELING)
                        .map(PreorderHistoryEntry::eventSequence)
                        .filter(cancelSequence::equals)
                        .isPresent();
    }

    /** 예약 행은 이미 잠겨 있다. CANCEL 작업이 이미 있으면 만들지 않는다 — 같은 결과를 두 번 받아도 작업은 하나다. */
    private void requestExternalCancel(PreorderSnapshot preorder) {
        if (syncJobs.exists(preorder.id(), SyncJobType.CANCEL)) {
            return;
        }
        String payload = jsonMapper.writeValueAsString(new CancelRequestPayload(preorder.preorderToken(),
                preorder.externalReference(), mockCancelReason(preorder.id())));
        Long jobId = syncJobs.createCancel(preorder.id(), payload);
        outboxWriter.append(new CancelJobReady(jobId, preorder.preorderToken()));
    }

    /** 이력에 남길 거절 사유. order 가 사유를 주지 않았으면 결과만 남긴다. */
    private String rejectionReason(String orderReason) {
        return orderReason == null ? REJECTED_BY_ORDER : REJECTED_BY_ORDER + ":" + orderReason;
    }

    /** 취소를 시작한 이력의 주체로 Mock 감사 사유를 정한다. */
    private String mockCancelReason(Long preorderId) {
        return preorders.lastTransitionTo(preorderId, PreorderStatus.CANCELING)
                .map(PreorderHistoryEntry::actor)
                .map(MOCK_CANCEL_REASONS::get)
                .orElse(null);
    }
}
