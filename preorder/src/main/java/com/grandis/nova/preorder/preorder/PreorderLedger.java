package com.grandis.nova.preorder.preorder;

import com.grandis.nova.preorder.preorder.domain.Preorder;
import com.grandis.nova.preorder.preorder.domain.PreorderEvent;
import com.grandis.nova.preorder.preorder.domain.PreorderRepository;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * 예약 상태를 바꾸는 유일한 길. 호출하는 쪽은 사건({@link PreorderFact})만 알리고,
 * 다음 상태는 상태 머신({@link PreorderStatus#next})이 정한다.
 *
 * 사건 하나의 처리:
 * 예약 행 잠금 읽기 → 상태 머신 판정 → 현재 상태 조건부 UPDATE(이력 번호 증가) → 이력 INSERT.
 * 이 모두가 호출한 쪽의 트랜잭션 하나에서 일어난다. 이력 없이 상태만 바뀌는 경로를 만들지 않으려고 전이를 여기로 모았다.
 *
 * 스스로 트랜잭션을 열지 않는다(MANDATORY). 전이는 늘 다른 변경(작업 행 · 아웃박스)과 한 트랜잭션이어야 해서,
 * 여기서 따로 커밋되면 그 원자성이 깨진다.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class PreorderLedger {

    private final PreorderRepository preorders;
    private final EntityManager entityManager;
    private final Clock clock;

    PreorderLedger(PreorderRepository preorders, EntityManager entityManager, Clock clock) {
        this.preorders = preorders;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    /**
     * 새 예약과 첫 이력(번호 1, from 없음)을 저장한다.
     * UNIQUE 충돌(같은 모델 활성 예약 · 쓴 입장권 · 같은 접수 키)은 여기서 삼키지 않고 그대로 올려 보낸다 —
     * 어느 제약인지에 따라 응답이 달라서 접수 유스케이스가 판정한다.
     */
    public PreorderSnapshot accept(NewPreorder draft, EventActor actor, String reason) {
        actor.requireReason(reason);
        Preorder preorder = preorders.saveAndFlush(new Preorder(draft));
        entityManager.persist(new PreorderEvent(preorder.getId(), PreorderEvent.FIRST_SEQUENCE,
                null, PreorderStatus.PENDING_SYNC, actor, reason, clock.instant()));
        return preorder.toSnapshot();
    }

    /** 예약 행을 잠그고 읽는다. 판정과 전이 사이에 다른 변경이 끼지 못하게 할 때 쓴다(만료 등). */
    public Optional<PreorderSnapshot> lockByToken(String preorderToken) {
        return preorders.findForUpdateByPreorderToken(preorderToken).map(Preorder::toSnapshot);
    }

    /** 관리자 전용 메모. 이력을 남기지 않는 유일한 변경이다. @throws BusinessException PREORDER_NOT_FOUND */
    public void changeInternalNote(String preorderToken, String internalNote) {
        preorders.getByToken(preorderToken).changeInternalNote(internalNote);
    }

    /**
     * 사건을 적용한다. 지금 상태에서 의미 없는 사건이면(중복 · 늦은 도착) 아무것도 바꾸지 않고
     * applied = false 와 지금 상태를 돌려준다 — 같은 메시지를 두 번 받아도 결과가 같다.
     * 상태가 그대로인 사건(결제 시작 · 취소 중 결제 확인)은 시각만 남기고 이력은 남기지 않는다.
     *
     * @throws IllegalArgumentException 예약이 없다 — 호출하는 쪽이 먼저 확인한다
     * @throws IllegalStateException    주문 쪽 취소 거절인데 결제 가능한 적이 없는 예약이다
     */
    public PreorderTransition fire(Long preorderId, PreorderFact fact) {
        fact.actor().requireReason(fact.reason());
        PreorderStatus from = lockStatus(preorderId);
        PreorderStatus to = from.next(fact.trigger()).orElse(null);
        if (to == null) {
            return new PreorderTransition(false, from);
        }
        Instant now = clock.instant();
        PreorderStatus reached = apply(preorderId, fact, from, to, now);
        if (reached != from) {
            record(preorderId, from, reached, fact.actor(), fact.reason(), now);
        }
        return new PreorderTransition(true, reached);
    }

    /** 사건마다 예약 행에 남기는 값이 다르다. 사건이 늘면 컴파일러가 빠진 곳을 알린다. @return 실제로 간 상태 */
    private PreorderStatus apply(Long preorderId, PreorderFact fact, PreorderStatus from, PreorderStatus to,
                                 Instant now) {
        switch (fact) {
            case PreorderFact.RegisterConfirmed confirmed -> requireOneRow(
                    preorders.markPayable(preorderId, confirmed.externalReference(), now, from, to), preorderId);
            case PreorderFact.PaymentStarted started -> requireOneRow(
                    preorders.markPaymentStarted(preorderId, started.startedAt(), now, from), preorderId);
            case PreorderFact.PaymentConfirmed paid when to == from -> requireOneRow(
                    preorders.recordPaid(preorderId, paid.paidAt(), now, from), preorderId);
            case PreorderFact.PaymentConfirmed paid -> requireOneRow(
                    preorders.markReserved(preorderId, paid.paidAt(), now, from, to), preorderId);
            case PreorderFact.CancelRejected rejected -> {
                return revertCancel(preorderId, rejected.paidAt(), from, now);
            }
            case PreorderFact.CancelRequested _, PreorderFact.CancelCompleted _ ->
                    requireOneRow(preorders.changeStatus(preorderId, from, to, now), preorderId);
        }
        return to;
    }

    /**
     * 예약 행을 잠그고 지금 상태를 읽는다. 전이 없이 예약과 순서를 맞춰야 하는 변경(작업 재처리 등)이 쓴다.
     *
     * @throws IllegalArgumentException 예약이 없다
     */
    public PreorderStatus lockStatus(Long preorderId) {
        return preorders.findStatusForUpdate(preorderId)
                .orElseThrow(() -> new IllegalArgumentException("예약이 없다: " + preorderId));
    }

    /**
     * 취소 거절 → 결제 확인된 예약이면 RESERVED, 아니면 PAYABLE 로 되돌린다. 거절에 결제 시각이 실려 오면 먼저 남긴다 —
     * 결제 확인 이벤트보다 먼저 와도 확정으로 돌아가 만료 대상이 되지 않게.
     * 거절은 주문이 있을 때만 오고 주문은 PAYABLE 이후에만 생기므로, 결제 가능한 적이 없는 예약이 거절되면 어딘가 잘못된 것이다.
     */
    private PreorderStatus revertCancel(Long preorderId, Instant paidAt, PreorderStatus from, Instant now) {
        if (paidAt != null) {
            requireOneRow(preorders.recordPaid(preorderId, paidAt, now, from), preorderId);
        }
        PreorderStatus back = preorders.findReservedAt(preorderId).isPresent()
                ? PreorderStatus.RESERVED : PreorderStatus.PAYABLE;
        if (preorders.revertCancel(preorderId, now, from, back) != 1) {
            throw new IllegalStateException(
                    "결제 가능한 적이 없는 예약의 취소는 거절될 수 없다: preorderId=" + preorderId);
        }
        return back;
    }

    /** 행을 잠근 채 읽은 상태를 조건으로 하므로 늘 1행이다. 0 이면 잠금 규칙이 깨진 것이다. */
    private void requireOneRow(int updated, Long preorderId) {
        if (updated != 1) {
            throw new IllegalStateException("잠근 예약의 상태가 바뀌었다: preorderId=" + preorderId);
        }
    }

    private void record(Long preorderId, PreorderStatus from, PreorderStatus to,
                        EventActor actor, String reason, Instant now) {
        long sequence = preorders.findEventSequence(preorderId);
        entityManager.persist(new PreorderEvent(preorderId, sequence, from, to, actor, reason, now));
    }
}
