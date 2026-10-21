package com.grandis.nova.preorder.cancel.application;

import com.grandis.nova.common.outbox.OutboxWriter;
import com.grandis.nova.preorder.preorder.CancelReason;
import com.grandis.nova.preorder.preorder.EventActor;
import com.grandis.nova.preorder.preorder.PreorderFact;
import com.grandis.nova.preorder.preorder.LockedPreorder;
import com.grandis.nova.preorder.preorder.PreorderLedger;
import com.grandis.nova.preorder.preorder.PreorderSnapshot;
import com.grandis.nova.preorder.preorder.PreorderTransition;
import com.grandis.nova.preorder.syncjob.SyncJobs;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * 취소 시작 트랜잭션. 사용자 · 관리자 · 만료 · 회차 취소가 모두 이 경로를 탄다.
 *
 * 예약 → CANCELING, 끝나지 않은 REGISTER 작업 무효화, PREORDER_CANCEL_REQUESTED 기록을 한 트랜잭션에서 한다.
 * 잠금 순서는 예약 행 → 그 예약의 작업 행이다(원장이 먼저 예약 행을 잠근다).
 * 주문 정리와 외부 취소는 이 뒤에 이벤트로 이어진다 — 여기서 외부를 부르지 않는다.
 */
@Component
public class CancelStarter {

    private final PreorderLedger ledger;
    private final SyncJobs syncJobs;
    private final OutboxWriter outboxWriter;
    private final Clock clock;

    public CancelStarter(PreorderLedger ledger, SyncJobs syncJobs, OutboxWriter outboxWriter, Clock clock) {
        this.ledger = ledger;
        this.syncJobs = syncJobs;
        this.outboxWriter = outboxWriter;
        this.clock = clock;
    }

    /** 이미 취소 중 · 취소 완료면 아무것도 만들지 않고 지금 상태를 돌려준다. */
    @Transactional
    public PreorderTransition start(PreorderSnapshot preorder, EventActor actor, String reason, CancelReason cancelReason) {
        return start(ledger.lock(preorder.id()), actor, reason, cancelReason);
    }

    /**
     * 이미 잠근 예약의 취소를 시작한다. 시도 순번은 이번에 남기는 CANCELING 진입 이력의 순번이다(잠글 때 순번 + 1).
     */
    @Transactional
    public PreorderTransition start(LockedPreorder preorder, EventActor actor, String reason,
                                    CancelReason cancelReason) {
        PreorderTransition transition = ledger.fire(preorder, new PreorderFact.CancelRequested(actor, reason));
        if (transition.applied()) {
            syncJobs.cancelRegister(preorder.id(), clock.instant());
            outboxWriter.append(new PreorderCancelRequested(preorder.id(), preorder.preorderToken(),
                    preorder.customerId(), cancelReason, preorder.nextEventSequence()));
        }
        return transition;
    }
}
