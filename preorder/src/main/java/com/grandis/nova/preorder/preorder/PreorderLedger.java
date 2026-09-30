package com.grandis.nova.preorder.preorder;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * 예약 상태를 바꾸는 유일한 길. 호출하는 쪽은 사건({@link PreorderTrigger})만 알리고,
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
        return PreorderSnapshot.of(preorder);
    }

    /** 예약 행을 잠그고 읽는다. 판정과 전이 사이에 다른 변경이 끼지 못하게 할 때 쓴다(만료 등). */
    public Optional<PreorderSnapshot> lockByToken(String preorderToken) {
        return preorders.findForUpdateByPreorderToken(preorderToken).map(PreorderSnapshot::of);
    }

    /** 관리자 전용 메모. 이력을 남기지 않는 유일한 변경이다. @throws BusinessException PREORDER_NOT_FOUND */
    public void changeInternalNote(String preorderToken, String internalNote) {
        preorders.getByToken(preorderToken).changeInternalNote(internalNote);
    }

    /**
     * 사건을 적용한다. 지금 상태에서 의미 없는 사건이면(중복 · 늦은 도착) 아무것도 바꾸지 않고
     * applied = false 와 지금 상태를 돌려준다 — 같은 메시지를 두 번 받아도 결과가 같다.
     * 외부 등록 확인은 외부 예약 번호가 필요하므로 {@link #confirmRegister} 를 쓴다.
     *
     * @throws IllegalArgumentException 예약이 없다 — 호출하는 쪽이 먼저 확인한다
     * @throws IllegalStateException    주문 쪽 취소 거절인데 결제 가능한 적이 없는 예약이다
     */
    public PreorderTransition fire(Long preorderId, PreorderTrigger trigger, EventActor actor, String reason) {
        if (trigger == PreorderTrigger.REGISTER_CONFIRMED) {
            throw new IllegalArgumentException("등록 확인은 confirmRegister 를 쓴다");
        }
        actor.requireReason(reason);
        PreorderStatus from = lockStatus(preorderId);
        PreorderStatus to = from.next(trigger).orElse(null);
        if (to == null) {
            return new PreorderTransition(false, from);
        }
        Instant now = clock.instant();
        if (trigger == PreorderTrigger.CANCEL_REJECTED) {
            revertToPayable(preorderId, from, to, now);
        } else {
            requireOneRow(preorders.changeStatus(preorderId, from, to, now), preorderId);
        }
        record(preorderId, from, to, actor, reason, now);
        return new PreorderTransition(true, to);
    }

    /**
     * 외부 등록 확인 → PAYABLE. payable_from 과 외부 예약 번호를 함께 채운다.
     * 이미 반영됐거나 취소 중이면 바꾸지 않는다 — 늦게 도착한 등록 성공이 취소된 예약을 되살리지 않는다.
     */
    public PreorderTransition confirmRegister(Long preorderId, String externalReference) {
        PreorderStatus from = lockStatus(preorderId);
        PreorderStatus to = from.next(PreorderTrigger.REGISTER_CONFIRMED).orElse(null);
        if (to == null) {
            return new PreorderTransition(false, from);
        }
        Instant now = clock.instant();
        requireOneRow(preorders.markPayable(preorderId, externalReference, now, from, to), preorderId);
        record(preorderId, from, to, EventActor.SYSTEM, null, now);
        return new PreorderTransition(true, to);
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
     * 취소 거절은 주문이 있을 때만 온다. 주문은 PAYABLE 이후에만 생기므로 결제 가능한 적이 없는 예약
     * (PENDING_SYNC 에서 시작한 취소)이 거절되면 어딘가 잘못된 것이다 — 그 예약을 PAYABLE 로 만들지 않는다.
     */
    private void revertToPayable(Long preorderId, PreorderStatus from, PreorderStatus to, Instant now) {
        if (preorders.revertToPayable(preorderId, now, from, to) != 1) {
            throw new IllegalStateException(
                    "결제 가능한 적이 없는 예약의 취소는 거절될 수 없다: preorderId=" + preorderId);
        }
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
