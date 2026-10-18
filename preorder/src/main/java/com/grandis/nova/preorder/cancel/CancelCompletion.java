package com.grandis.nova.preorder.cancel;

import com.grandis.nova.common.outbox.OutboxWriter;
import com.grandis.nova.preorder.outbox.NotificationRequested;
import com.grandis.nova.preorder.preorder.EventActor;
import com.grandis.nova.preorder.preorder.PreorderFact;
import com.grandis.nova.preorder.preorder.PreorderHistoryEntry;
import com.grandis.nova.preorder.preorder.PreorderLedger;
import com.grandis.nova.preorder.preorder.PreorderSnapshot;
import com.grandis.nova.preorder.preorder.PreorderStatus;
import com.grandis.nova.preorder.preorder.Preorders;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * 취소를 끝낸다 — 예약을 CANCELED 로 하고 같은 트랜잭션에서 회원 알림(PREORDER_CANCELED)을 적는다.
 * 외부 취소 성공(worker)과 포기한 취소의 관리자 수동 완료가 같은 결과를 내도록 둘 다 여기를 거친다.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class CancelCompletion {

    /** 취소를 시작한 주체 → 취소 알림 문구. 관리자 취소와 회차 판매 중지는 둘 다 ADMIN 이고 문구도 같다. */
    private static final Map<EventActor, NotificationRequested.CancelCause> CANCEL_CAUSES = Map.of(
            EventActor.USER, NotificationRequested.CancelCause.USER,
            EventActor.ADMIN, NotificationRequested.CancelCause.OTHER,
            EventActor.SYSTEM, NotificationRequested.CancelCause.EXPIRY);

    private final PreorderLedger ledger;
    private final Preorders preorders;
    private final OutboxWriter outboxWriter;

    CancelCompletion(PreorderLedger ledger, Preorders preorders, OutboxWriter outboxWriter) {
        this.ledger = ledger;
        this.preorders = preorders;
        this.outboxWriter = outboxWriter;
    }

    /**
     * 알림은 예약이 실제로 CANCELED 가 된 때만 적는다. member 는 eventId 로만 중복을 거르는데 아웃박스 행마다 새 eventId 라,
     * 같은 결과를 다시 받을 때 또 적으면 알림이 두 건이 된다.
     *
     * @return CANCELED 로 바꿨으면 true, 이미 끝났거나 취소 중이 아니면 false
     */
    public boolean complete(UUID preorderId, EventActor actor, String reason) {
        if (!ledger.fire(preorderId, new PreorderFact.CancelCompleted(actor, reason)).applied()) {
            return false;
        }
        notifyCanceled(preorderId);
        return true;
    }

    /**
     * 까닭은 이번 취소 시도의 CANCELING 진입 이력 주체로 정한다(예약 행에 사유 칸이 없다). 결제 여부는 결제 확인 시각으로 본다 —
     * 결제 확인 이벤트가 취소 완료보다 늦게 오면 환불 문장이 빠지지만, 그런 순서는 드물고 문구만 덜 자세할 뿐이다.
     */
    private void notifyCanceled(UUID preorderId) {
        PreorderSnapshot preorder = preorders.getById(preorderId);
        NotificationRequested.CancelCause cause = preorders.lastTransitionTo(preorderId, PreorderStatus.CANCELING)
                .map(PreorderHistoryEntry::actor)
                .map(CANCEL_CAUSES::get)
                .orElse(NotificationRequested.CancelCause.OTHER);
        outboxWriter.append(NotificationRequested.preorderCanceled(preorder.id(), preorder.preorderToken(),
                preorder.customerId(), cause, preorder.reservedAt() != null));
    }
}
