package com.grandis.nova.order.outbox;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.Objects;

/**
 * 예약 취소(PREORDER_CANCEL_REQUESTED)에 따른 주문 정리 결과. preorder 의 {@code event.PreorderOrderSettled} 가 받는다.
 *
 * payload 모양은 받는 쪽 record 와 같아야 한다: {@code {preorderId, result, reason, cancelSequence}}.
 * 받는 쪽은 cancelSequence 가 없으면 반영하지 않고 실패한다 — 그러면 order 는 이미 커밋해 되돌릴 수 없으므로,
 * 계약에 맞지 않는 조합은 여기서 생성할 때 막아 보내는 트랜잭션을 실패시킨다.
 *
 * @param preorderInternalId 예약 내부 id. 봉투의 aggregateId 로만 나가고 payload 에는 싣지 않는다
 * @param preorderId         공개 UUID(preorder_token). 받는 쪽이 이것으로 예약을 찾는다
 * @param reason             REJECTED 일 때만 있다
 * @param cancelSequence     PREORDER_CANCEL_REQUESTED 에서 받은 값 그대로. order 는 저장하지 않는다
 */
public record PreorderOrderSettled(@JsonIgnore Long preorderInternalId, String preorderId, Result result,
                                   RejectReason reason, Long cancelSequence) implements OutboxMessage {

    public PreorderOrderSettled {
        Objects.requireNonNull(preorderInternalId, "preorderInternalId");
        Objects.requireNonNull(result, "result");
        if (preorderId == null || preorderId.isBlank()) {
            throw new IllegalArgumentException("preorderId 가 없다");
        }
        if (cancelSequence == null) {
            throw new IllegalArgumentException("cancelSequence 가 없다: preorderId=" + preorderId);
        }
        if ((result == Result.REJECTED) != (reason != null)) {
            throw new IllegalArgumentException("거절일 때만 사유가 있다: result=" + result + ", reason=" + reason);
        }
    }

    /** 그 예약의 주문이 없다. */
    public static PreorderOrderSettled noOrder(Long preorderInternalId, String preorderId, Long cancelSequence) {
        return new PreorderOrderSettled(preorderInternalId, preorderId, Result.NO_ORDER, null, cancelSequence);
    }

    /** 주문이 취소됐다. 이번에 취소했든 이미 취소돼 있었든(중복 수신) 같다. */
    public static PreorderOrderSettled canceled(Long preorderInternalId, String preorderId, Long cancelSequence) {
        return new PreorderOrderSettled(preorderInternalId, preorderId, Result.CANCELED, null, cancelSequence);
    }

    /** 주문을 정리할 수 없어 예약 취소를 거절한다. */
    public static PreorderOrderSettled rejected(Long preorderInternalId, String preorderId, RejectReason reason,
                                                Long cancelSequence) {
        return new PreorderOrderSettled(preorderInternalId, preorderId, Result.REJECTED,
                Objects.requireNonNull(reason, "reason"), cancelSequence);
    }

    @Override
    public OutboundEventType eventType() {
        return OutboundEventType.PREORDER_ORDER_SETTLED;
    }

    @Override
    public AggregateType aggregateType() {
        return AggregateType.PREORDER;
    }

    @Override
    public Long aggregateId() {
        return preorderInternalId;
    }

    /**
     * 받는 쪽 {@code PreorderOrderSettled.Result} 와 이름이 같아야 한다. 이 enum 과 RejectReason 은 preorder 에 한 벌 더 있다
     * (사유는 그쪽에서 문자열). 계약 record 를 common:message 로 옮기면 한 벌로 줄이고, 시험의 복제 record 도 지운다.
     */
    public enum Result {
        NO_ORDER,
        CANCELED,
        REJECTED
    }

    /** 거절 사유. 받는 쪽은 문자열로 받아 예약 이력에 남긴다. */
    public enum RejectReason {
        /** 배송이 시작됐다. */
        SHIPPED,
        /** 만료 취소인데 그사이 결제됐다(만료 경합). 환불하지 않는다. */
        PAID
    }
}
