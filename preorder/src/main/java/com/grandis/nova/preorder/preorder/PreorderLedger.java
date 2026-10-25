package com.grandis.nova.preorder.preorder;

import com.grandis.nova.common.UuidBinary;
import com.grandis.nova.preorder.preorder.domain.PaymentAnomalyStore;
import com.grandis.nova.preorder.preorder.domain.Preorder;
import com.grandis.nova.preorder.preorder.domain.PreorderEvent;
import com.grandis.nova.preorder.preorder.domain.PreorderLockQuery;
import com.grandis.nova.preorder.preorder.domain.PreorderRepository;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 예약 상태를 바꾸는 유일한 길. 호출하는 쪽은 사건({@link PreorderFact})만 알리고,
 * 다음 상태는 상태 머신({@link PreorderStatus#next})이 정한다.
 *
 * 사건 하나의 처리:
 * 예약 행 잠금 읽기(상태 · 순번 · 결제 시각) → 상태 머신 판정 → 현재 상태 조건부 UPDATE(이력 번호 증가) → 이력 INSERT(잠글 때 순번 + 1).
 * 이 모두가 호출한 쪽의 트랜잭션 하나에서 일어난다. 이력 없이 상태만 바뀌는 경로를 만들지 않으려고 전이를 여기로 모았다.
 *
 * 스스로 트랜잭션을 열지 않는다(MANDATORY). 전이는 늘 다른 변경(작업 행 · 아웃박스)과 한 트랜잭션이어야 해서,
 * 여기서 따로 커밋되면 그 원자성이 깨진다.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class PreorderLedger {

    private final PreorderRepository preorders;
    private final PreorderLockQuery lockQuery;
    private final PaymentAnomalyStore paymentAnomalies;
    private final EntityManager entityManager;
    private final Clock clock;

    PreorderLedger(PreorderRepository preorders, PreorderLockQuery lockQuery, PaymentAnomalyStore paymentAnomalies,
                   EntityManager entityManager, Clock clock) {
        this.preorders = preorders;
        this.lockQuery = lockQuery;
        this.paymentAnomalies = paymentAnomalies;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    /**
     * 새 예약(순번 · 차수 없음)과 첫 이력(번호 1, from 없음)을 저장하고 예약 id 를 돌려준다. 순번 · 차수는 같은 트랜잭션에서
     * {@link #place} 로 채운다 — 비운 채 커밋하지 않는다.
     * UNIQUE 충돌(같은 모델 활성 예약 · 쓴 입장권 · 같은 접수 키)은 여기서 삼키지 않고 그대로 올려 보낸다 —
     * 어느 제약인지에 따라 응답이 달라서 접수 유스케이스가 판정한다.
     */
    public UUID accept(NewPreorder draft, EventActor actor, String reason) {
        actor.requireReason(reason);
        Preorder preorder = preorders.saveAndFlush(new Preorder(draft));
        entityManager.persist(new PreorderEvent(preorder.getId(), PreorderEvent.FIRST_SEQUENCE,
                null, PreorderStatus.PENDING_SYNC, actor, reason, clock.instant()));
        return preorder.getId();
    }

    /**
     * {@link #accept} 로 넣은 예약에 순번 · 차수를 채운다. 저장소 쿼리라 외래 키 · UNIQUE 위반에 제약 이름이 남는다.
     *
     * @throws IllegalStateException 채울 예약이 없다
     */
    public PreorderSnapshot place(UUID preorderId, UUID shipmentBatchId, long queuePosition) {
        int placed = preorders.place(UuidBinary.toBytes(preorderId), UuidBinary.toBytes(shipmentBatchId),
                queuePosition);
        Preorder preorder = entityManager.find(Preorder.class, preorderId);
        if (placed != 1 || preorder == null) {
            throw new IllegalStateException("순번을 채울 예약이 없다: preorderId=" + preorderId);
        }
        preorder.place(shipmentBatchId, queuePosition);
        return preorder.toSnapshot();
    }

    /**
     * 예약 행을 잠그고 전이에 필요한 값을 함께 읽는다. 판정과 전이 사이에 다른 변경이 끼지 못하게 할 때 쓰고,
     * 받은 값을 {@link #fire(LockedPreorder, PreorderFact)} 에 넘기면 다시 잠그거나 읽지 않는다.
     *
     * @throws IllegalArgumentException 예약이 없다
     */
    public LockedPreorder lock(UUID preorderId) {
        return lockQuery.lock(preorderId)
                .orElseThrow(() -> new IllegalArgumentException("예약이 없다: " + preorderId));
    }

    /** 공개 UUID 로 {@link #lock}. 없으면 비어 있다. */
    public Optional<LockedPreorder> lockByToken(String preorderToken) {
        return lockQuery.lockByToken(preorderToken);
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
    public PreorderTransition fire(UUID preorderId, PreorderFact fact) {
        return fire(lock(preorderId), fact);
    }

    /**
     * 이미 잠근 예약에 사건을 적용한다 — 다시 잠그지 않고, 이력 순번은 잠글 때의 순번 + 1 이다.
     * 잠근 뒤 전이 없이 넘긴다(전이한 뒤의 값은 옛 값이다).
     */
    public PreorderTransition fire(LockedPreorder locked, PreorderFact fact) {
        fact.actor().requireReason(fact.reason());
        PreorderStatus from = locked.status();
        PreorderStatus to = from.next(fact.trigger()).orElse(null);
        if (to == null) {
            return new PreorderTransition(false, from);
        }
        Instant now = clock.instant();
        PreorderStatus reached = apply(locked, fact, from, to, now);
        if (reached != from) {
            entityManager.persist(new PreorderEvent(locked.id(), locked.nextEventSequence(), from, reached,
                    fact.actor(), fact.reason(), now));
        }
        return new PreorderTransition(true, reached);
    }

    /** 사건마다 예약 행에 남기는 값이 다르다. 사건이 늘면 컴파일러가 빠진 곳을 알린다. @return 실제로 간 상태 */
    private PreorderStatus apply(LockedPreorder locked, PreorderFact fact, PreorderStatus from, PreorderStatus to,
                                 Instant now) {
        UUID preorderId = locked.id();
        switch (fact) {
            case PreorderFact.RegisterConfirmed confirmed -> requireOneRow(
                    preorders.markRegistered(preorderId, confirmed.externalReference(), now, from, to), preorderId);
            case PreorderFact.PaymentStarted started -> requireOneRow(
                    preorders.markPaymentStarted(preorderId, started.startedAt(), now, from), preorderId);
            case PreorderFact.PaymentConfirmed paid when to == from -> requireOneRow(
                    preorders.recordPaid(preorderId, paid.paidAt(), now, from), preorderId);
            case PreorderFact.PaymentConfirmed paid -> requireOneRow(
                    preorders.markReserved(preorderId, paid.paidAt(), now, from, to), preorderId);
            case PreorderFact.CancelRejected rejected -> {
                return revertCancel(locked, rejected.paidAt(), from, now);
            }
            case PreorderFact.CancelRequested _, PreorderFact.CancelCompleted _ ->
                    requireOneRow(preorders.changeStatus(preorderId, from, to, now), preorderId);
        }
        return to;
    }

    /**
     * 취소가 끝난 예약에 온 결제 확인을 남긴다. 예약 행을 잠근 트랜잭션에서 부른다 — 같은 결제가 겹쳐 와도 한쪽만 새로 남긴다.
     *
     * @return 처음 받은 결제면 true, 이미 남긴 결제면 false
     * @throws IllegalArgumentException 결제 시각이 없다 — 어느 결제인지 가를 수 없다
     */
    public boolean recordPaymentAfterCancel(UUID preorderId, String orderId, Instant paidAt) {
        if (paidAt == null) {
            throw new IllegalArgumentException("결제 시각이 없는 결제 확인: preorderId=" + preorderId);
        }
        return paymentAnomalies.record(preorderId, paidAt, orderId, clock.instant());
    }

    /**
     * 예약 행을 잠그고 지금 상태를 읽는다. 전이 없이 예약과 순서를 맞춰야 하는 변경(작업 재처리 등)이 쓴다.
     *
     * @throws IllegalArgumentException 예약이 없다
     */
    public PreorderStatus lockStatus(UUID preorderId) {
        return lock(preorderId).status();
    }

    /**
     * 취소 거절 → 결제 확인된 예약이면 RESERVED, 아니면 REGISTERED 로 되돌린다. 거절에 결제 시각이 실려 오면 먼저 남긴다 —
     * 결제 확인 이벤트보다 먼저 와도 확정으로 돌아가 만료 대상이 되지 않게.
     * 거절은 주문이 있을 때만 오고 주문은 REGISTERED 이후에만 생기므로, 결제 가능한 적이 없는 예약이 거절되면 어딘가 잘못된 것이다.
     */
    private PreorderStatus revertCancel(LockedPreorder locked, Instant paidAt, PreorderStatus from, Instant now) {
        UUID preorderId = locked.id();
        if (paidAt != null) {
            requireOneRow(preorders.recordPaid(preorderId, paidAt, now, from), preorderId);
        }
        PreorderStatus back = locked.reservedAt() != null || paidAt != null
                ? PreorderStatus.RESERVED : PreorderStatus.REGISTERED;
        if (preorders.revertCancel(preorderId, now, from, back) != 1) {
            throw new IllegalStateException(
                    "결제 가능한 적이 없는 예약의 취소는 거절될 수 없다: preorderId=" + preorderId);
        }
        return back;
    }

    /** 행을 잠근 채 읽은 상태를 조건으로 하므로 늘 1행이다. 0 이면 잠금 규칙이 깨진 것이다. */
    private void requireOneRow(int updated, UUID preorderId) {
        if (updated != 1) {
            throw new IllegalStateException("잠근 예약의 상태가 바뀌었다: preorderId=" + preorderId);
        }
    }
}
