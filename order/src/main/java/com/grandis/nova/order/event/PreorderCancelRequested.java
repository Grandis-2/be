package com.grandis.nova.order.event;

import com.grandis.nova.order.order.cancel.CancelReason;
import com.grandis.nova.order.order.cancel.SettlePreorderCancelCommand;

/**
 * PREORDER_CANCEL_REQUESTED 의 payload(preorder outbox.OutboxMessage.PreorderCancelRequested 가 보낸다).
 * 예약 내부 id 는 payload 에 없고 봉투의 aggregateId 로만 온다.
 *
 * 칸이 빠지면 결과를 돌려줄 수 없으므로(특히 cancelSequence — 없으면 preorder 가 결과를 받지 못한다) 풀 때 실패시킨다.
 *
 * @param preorderId 예약 공개 UUID. 주문을 찾는 데 쓰지 않는다
 */
public record PreorderCancelRequested(String preorderId, Long customerId, CancelReason reason, Long cancelSequence) {

    public PreorderCancelRequested {
        if (preorderId == null || preorderId.isBlank() || customerId == null || reason == null
                || cancelSequence == null) {
            throw new IllegalArgumentException("취소 요청에 빠진 칸이 있다: preorderId=%s, customerId=%s, reason=%s, cancelSequence=%s"
                    .formatted(preorderId, customerId, reason, cancelSequence));
        }
    }

    SettlePreorderCancelCommand toCancel(Long preorderInternalId) {
        return new SettlePreorderCancelCommand(preorderInternalId, preorderId, customerId, reason, cancelSequence);
    }
}
