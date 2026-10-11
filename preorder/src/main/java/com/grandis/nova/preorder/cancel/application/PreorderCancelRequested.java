package com.grandis.nova.preorder.cancel.application;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.grandis.nova.preorder.outbox.AggregateType;
import com.grandis.nova.preorder.outbox.OutboundEventType;
import com.grandis.nova.preorder.outbox.OutboxMessage;
import com.grandis.nova.preorder.preorder.CancelReason;

import java.util.UUID;

/**
 * 취소 시작 트랜잭션. order 가 주문을 정리하고 PREORDER_ORDER_SETTLED 로 답한다.
 *
 * @param preorderInternalId 원장 조회용 예약 내부 id. 봉투의 aggregateId 로만 나가고 payload 에는 싣지 않는다
 * @param preorderId         공개 UUID(preorder_token)
 * @param cancelSequence     이 취소 시도의 CANCELING 진입 이력 event_sequence. order 가 PREORDER_ORDER_SETTLED 에
 *                           그대로 돌려주고, preorder 는 지금 시도의 결과인지 이것으로 가린다
 */
public record PreorderCancelRequested(@JsonIgnore UUID preorderInternalId, String preorderId, UUID customerId,
                                      CancelReason reason, Long cancelSequence) implements OutboxMessage {

    @Override
    public OutboundEventType eventType() {
        return OutboundEventType.PREORDER_CANCEL_REQUESTED;
    }

    @Override
    public AggregateType aggregateType() {
        return AggregateType.PREORDER;
    }

    @Override
    public UUID aggregateId() {
        return preorderInternalId;
    }
}
