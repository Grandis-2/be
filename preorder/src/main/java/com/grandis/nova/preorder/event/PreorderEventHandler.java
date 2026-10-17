package com.grandis.nova.preorder.event;

import com.grandis.nova.common.outbox.OutboxWriter;
import com.grandis.nova.preorder.cancel.CancelCompletion;
import com.grandis.nova.preorder.cancel.CancelRequestPayload;
import com.grandis.nova.preorder.outbox.NotificationRequested;
import com.grandis.nova.preorder.preorder.EventActor;
import com.grandis.nova.preorder.preorder.PreorderFact;
import com.grandis.nova.preorder.preorder.PreorderHistoryEntry;
import com.grandis.nova.preorder.preorder.PreorderLedger;
import com.grandis.nova.preorder.preorder.PreorderSnapshot;
import com.grandis.nova.preorder.preorder.PreorderStatus;
import com.grandis.nova.preorder.preorder.PreorderTransition;
import com.grandis.nova.preorder.preorder.Preorders;
import com.grandis.nova.preorder.syncjob.CancelJobReady;
import com.grandis.nova.preorder.syncjob.NewSyncAttempt;
import com.grandis.nova.preorder.syncjob.SyncActor;
import com.grandis.nova.preorder.syncjob.SyncJobSnapshot;
import com.grandis.nova.preorder.syncjob.SyncJobStatus;
import com.grandis.nova.preorder.syncjob.SyncJobType;
import com.grandis.nova.preorder.syncjob.SyncJobs;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * preorder 가 받는 이벤트의 처리. 메시지 하나 = 트랜잭션 하나다.
 *
 * 같은 메시지를 두 번 받아도 결과가 같아야 한다(SQS 는 at-least-once). 상태 머신이 지금 상태에서 의미 없는 사건을
 * 무시하고, 작업은 예약 행을 잠근 채 있는지 먼저 본다. 순서도 보장되지 않으므로 판단은 원장으로 한다.
 * 외부 작업의 결과(성공 · 실패한 차례 · 포기)는 작업 원장에 남긴다 — worker 는 DB 를 쓰지 않는다.
 */
@Component
public class PreorderEventHandler {

    private static final Logger log = LoggerFactory.getLogger(PreorderEventHandler.class);

    static final String FAILED_METRIC = "preorder.sync.failed";
    static final String PAYMENT_AFTER_CANCEL_METRIC = "preorder.payment.after_cancel";

    private static final String REJECTED_BY_ORDER = "ORDER_REJECTED";

    /** 취소를 시작한 주체 → Mock 감사 사유. 만료는 SYSTEM 이 시작한다. */
    private static final Map<EventActor, String> MOCK_CANCEL_REASONS = Map.of(
            EventActor.USER, "USER_CANCEL",
            EventActor.ADMIN, "ADMIN_CANCEL",
            EventActor.SYSTEM, "DEADLINE_EXCEEDED");

    private final Preorders preorders;
    private final SyncJobs syncJobs;
    private final PreorderLedger ledger;
    private final CancelCompletion cancelCompletion;
    private final OutboxWriter outboxWriter;
    private final JsonMapper jsonMapper;
    private final MeterRegistry meterRegistry;
    private final Counter paymentAfterCancel;
    private final Clock clock;

    public PreorderEventHandler(Preorders preorders,
                                SyncJobs syncJobs, PreorderLedger ledger, CancelCompletion cancelCompletion,
                                OutboxWriter outboxWriter, JsonMapper jsonMapper, MeterRegistry meterRegistry,
                                Clock clock) {
        this.preorders = preorders;
        this.syncJobs = syncJobs;
        this.ledger = ledger;
        this.cancelCompletion = cancelCompletion;
        this.outboxWriter = outboxWriter;
        this.jsonMapper = jsonMapper;
        this.meterRegistry = meterRegistry;
        this.paymentAfterCancel = meterRegistry.counter(PAYMENT_AFTER_CANCEL_METRIC);
        this.clock = clock;
    }

    /**
     * 외부 등록 · 취소 성공. 작업을 SUCCEEDED 로 바꾸고 예약에 반영한다. 포기한 뒤(DEAD_LETTER) 늦게 온 성공도 받는다.
     * 취소가 무효화한 등록 작업(CANCELED)이면 반영하지 않는다 — 외부 쪽은 CANCEL 작업이 정리한다.
     * 회원 알림은 예약 상태가 실제로 바뀐 때만 적는다. member 는 eventId 로만 중복을 거르는데 아웃박스 행마다 새 eventId 라,
     * 같은 결과를 다시 받을 때 또 적으면 알림이 두 건이 된다. 예약이 CANCELED 가 되는 길은 CANCEL 작업 성공과 관리자 수동 완료뿐이다.
     *
     * @param eventId 받은 이벤트의 id. 차례 기록을 한 번만 남기는 데 쓴다. 성공은 다시 반영해도 같아 차례 기록이 없어도 받는다
     */
    @Transactional
    public void onExternalJobSucceeded(String eventId, ExternalJobSucceeded message) {
        Optional<SyncJobSnapshot> found = lockJob(message.syncJobId());
        if (found.isEmpty()) {
            return;
        }
        SyncJobSnapshot job = found.get();
        if (message.attempt() != null) {
            syncJobs.recordAttempt(job.id(), eventId, message.attempt());
        }
        if (job.status() == SyncJobStatus.CANCELED) {
            log.warn("무효화된 작업의 성공 이벤트를 무시한다 syncJobId={}", job.id());
            return;
        }
        if (job.status() != SyncJobStatus.SUCCEEDED && syncJobs.succeed(job.id(), clock.instant()) == 0) {
            log.warn("성공으로 바꿀 수 없는 작업의 성공 이벤트를 무시한다 syncJobId={} status={}", job.id(), job.status());
            return;
        }
        if (job.jobType() == SyncJobType.REGISTER) {
            if (ledger.fire(job.preorderId(), new PreorderFact.RegisterConfirmed(message.externalNumber())).applied()) {
                notifyRegistered(job.preorderId());
            }
        } else {
            cancelCompletion.complete(job.preorderId(), EventActor.SYSTEM, null);
        }
    }

    private void notifyRegistered(UUID preorderId) {
        PreorderSnapshot preorder = preorders.getById(preorderId);
        outboxWriter.append(NotificationRequested.preorderRegistered(preorder.id(), preorder.preorderToken(),
                preorder.customerId()));
    }

    /** 실패한 차례가 있었고 worker 가 다시 시도한다. 차례를 남기고, 아직 끝나지 않은 작업이면 재시도 대기로 표시한다. */
    @Transactional
    public void onExternalJobAttempted(String eventId, ExternalJobAttempted message) {
        requireAttempt(message.attempt(), message.syncJobId());
        Optional<SyncJobSnapshot> job = lockJob(message.syncJobId());
        if (job.isPresent() && syncJobs.recordAttempt(job.get().id(), eventId, message.attempt())
                && isCurrentRun(job.get(), message.attempt())
                && syncJobs.scheduleRetry(job.get().id(), clock.instant()) == 0) {
            log.info("이미 끝난 작업의 차례는 기록만 한다 syncJobId={} status={}", job.get().id(), job.get().status());
        }
    }

    /**
     * worker 가 포기했다. 작업을 DEAD_LETTER 로 두고 관리자 재처리를 기다린다 — 예약은 그대로 둔다(취소면 CANCELING).
     * 취소를 포기해도 CANCELED 로 보내지 않는다 — 외부에 예약이 남아 있을 수 있다. 관리자가 재처리하거나 외부에서 확인하고 끝낸다.
     * 이미 받은 이벤트 · 지난 실행의 결과면 작업을 바꾸지 않는다 — 재처리로 되돌린 작업을 늦은 결과가 다시 포기시키지 않게.
     */
    @Transactional
    public void onExternalJobFailed(String eventId, ExternalJobFailed message) {
        requireAttempt(message.attempt(), message.syncJobId());
        Optional<SyncJobSnapshot> found = lockJob(message.syncJobId());
        if (found.isEmpty() || !syncJobs.recordAttempt(found.get().id(), eventId, message.attempt())
                || !isCurrentRun(found.get(), message.attempt())) {
            return;
        }
        SyncJobSnapshot job = found.get();
        if (syncJobs.deadLetter(job.id(), clock.instant()) == 0) {
            log.warn("이미 끝난 작업의 실패 이벤트를 무시한다 syncJobId={} status={}", job.id(), job.status());
            return;
        }
        countFailed(job, message.reason());
        if (job.jobType() == SyncJobType.CANCEL) {
            log.error("외부 취소를 포기했다 — 외부에 예약이 남아 있을 수 있다. 관리자 재처리 · 수동 완료를 기다린다 "
                    + "syncJobId={} preorderId={} reason={}", job.id(), message.preorderId(), message.reason());
            return;
        }
        log.warn("외부 등록을 포기했다 — 관리자 재처리를 기다린다 syncJobId={} preorderId={} reason={}",
                job.id(), message.preorderId(), message.reason());
    }

    private void countFailed(SyncJobSnapshot job, String reason) {
        meterRegistry.counter(FAILED_METRIC, "jobType", job.jobType().name(), "reason", String.valueOf(reason))
                .increment();
    }

    /**
     * 예약 행을 잠근 뒤 작업을 읽는다. 취소 · 재처리도 예약을 먼저 잠그므로 작업 상태 판단이 그 사이에 바뀌지 않는다.
     * 모르는 작업이면 비어 있다 — 다시 받아도 생기지 않으므로 버린다.
     */
    private Optional<SyncJobSnapshot> lockJob(UUID syncJobId) {
        Optional<UUID> preorderId = syncJobs.findPreorderId(syncJobId);
        if (preorderId.isEmpty()) {
            log.warn("모르는 작업의 결과 이벤트를 무시한다 syncJobId={}", syncJobId);
            return Optional.empty();
        }
        ledger.lockStatus(preorderId.get());
        return syncJobs.findById(syncJobId);
    }

    /**
     * 실패한 차례 · 포기는 차례 기록이 있어야 받는다 — 이미 받은 이벤트인지, 지난 실행의 결과인지를 그 기록으로 가린다.
     * 없으면 계약이 어긋난 것이라 예외로 올린다(재수신 한도를 넘으면 DLQ).
     */
    private static void requireAttempt(NewSyncAttempt attempt, UUID syncJobId) {
        if (attempt == null || attempt.run() < 1) {
            throw new IllegalArgumentException("차례 기록(attempt)이나 실행 회차(run)가 없는 결과 이벤트다: syncJobId=" + syncJobId);
        }
    }

    /** 관리자 재처리 전에 돌던 실행의 결과인가. 그런 결과는 차례 기록만 남기고 작업 상태는 바꾸지 않는다. */
    private static boolean isCurrentRun(SyncJobSnapshot job, NewSyncAttempt attempt) {
        if (attempt.run() >= job.run()) {
            return true;
        }
        log.warn("지난 실행의 결과라 작업 상태는 바꾸지 않는다 syncJobId={} run={} currentRun={}",
                job.id(), attempt.run(), job.run());
        return false;
    }

    /** 결제 시작. 화면의 "결제 진행 중" 표시용 시각만 남긴다 — 결제 가능 상태가 아니면 무시된다. */
    @Transactional
    public void onPaymentStarted(PreorderPaymentStarted message) {
        ledger.fire(requirePreorder(message.preorderId()).id(),
                new PreorderFact.PaymentStarted(message.startedAt()));
    }

    /**
     * 결제 확인 → 예약 확정. 취소 중이면 시각만 남겨 거절됐을 때 확정으로 돌아갈 근거로 둔다.
     * 취소가 끝난 예약에 남긴 결제 시각이 없거나 다른 결제 확인이 오면 order 가 취소한 주문을 승인한 것이다 — 상태는 그대로 두고
     * 이상 결제로 남겨 경보로 드러낸다. 같은 결제는 다시 · 겹쳐 와도 처음 남긴 행이 커밋될 때만 센다.
     * 결제 확인이 큐에서 늦어 환불 · 외부 취소 뒤에 처음 닿은 경우도 여기에 든다.
     */
    @Transactional
    public void onPaymentConfirmed(PreorderPaymentConfirmed message) {
        UUID preorderId = requirePreorder(message.preorderId()).id();
        PreorderFact.PaymentConfirmed paid = new PreorderFact.PaymentConfirmed(message.paidAt());
        PreorderTransition transition = ledger.fire(preorderId, paid);
        if (transition.applied() || transition.status() != PreorderStatus.CANCELED
                || isRecordedPayment(preorderId, paid.paidAt())) {
            return;
        }
        if (!ledger.recordPaymentAfterCancel(preorderId, message.orderId(), paid.paidAt())) {
            log.info("이미 남긴 이상 결제를 다시 받았다 preorderId={} paidAt={}", message.preorderId(), paid.paidAt());
            return;
        }
        // 이상 결제 행이 커밋된 뒤에만 알린다 — 롤백되면 재수신이 다시 남기고 그때 센다
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                paymentAfterCancel.increment();
                log.error("취소가 끝난 예약에 결제 확인이 왔다 — order 에서 환불 여부를 확인한다 preorderId={} orderId={} paidAt={}",
                        message.preorderId(), message.orderId(), message.paidAt());
            }
        });
    }

    /** 이미 남긴 결제 시각과 같은가. 같으면 취소 전에 반영한 결제 확인이 다시 온 것이다. 둘 다 저장 정밀도로 맞춘 값이다. */
    private boolean isRecordedPayment(UUID preorderId, Instant paidAt) {
        return paidAt != null && preorders.findReservedAt(preorderId).filter(paidAt::equals).isPresent();
    }

    /**
     * 주문 정리 결과. 주문이 없거나 취소됐으면 외부 취소 작업을 만들고, 거절이면 REGISTERED · RESERVED 로 되돌린다.
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
    private boolean isCurrentCancel(UUID preorderId, Long cancelSequence) {
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
        UUID jobId = syncJobs.createCancel(preorder.id(), payload);
        outboxWriter.append(new CancelJobReady(jobId, preorder.preorderToken(), payload, SyncActor.SYSTEM, 1));
    }

    /** 이력에 남길 거절 사유. order 가 사유를 주지 않았으면 결과만 남긴다. */
    private String rejectionReason(String orderReason) {
        return orderReason == null ? REJECTED_BY_ORDER : REJECTED_BY_ORDER + ":" + orderReason;
    }

    /** 취소를 시작한 이력의 주체로 Mock 감사 사유를 정한다. */
    private String mockCancelReason(UUID preorderId) {
        return preorders.lastTransitionTo(preorderId, PreorderStatus.CANCELING)
                .map(PreorderHistoryEntry::actor)
                .map(MOCK_CANCEL_REASONS::get)
                .orElse(null);
    }
}
