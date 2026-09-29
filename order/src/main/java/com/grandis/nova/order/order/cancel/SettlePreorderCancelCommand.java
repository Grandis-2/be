package com.grandis.nova.order.order.cancel;

import java.util.Objects;

/**
 * 예약 취소 요청 하나. 받은 봉투 · payload 에서 분배기가 만든다.
 *
 * @param preorderInternalId 봉투의 aggregateId(예약 내부 id = orders.preorder_id). 주문은 이것으로만 찾는다
 * @param preorderId         payload 의 공개 UUID. 찾는 데 쓰지 않고 결과에 그대로 돌려준다(preorder 가 이것으로 예약을 찾는다)
 * @param customerId         예약의 회원. 주문의 회원과 다르면 데이터가 어긋난 것이다
 * @param cancelSequence     어느 취소 시도인지. 저장하지 않고 결과에 그대로 돌려준다
 */
public record SettlePreorderCancelCommand(Long preorderInternalId, String preorderId, Long customerId,
                                          CancelReason reason, Long cancelSequence) {

    public SettlePreorderCancelCommand {
        Objects.requireNonNull(preorderInternalId, "preorderInternalId");
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(cancelSequence, "cancelSequence");
        if (preorderId == null || preorderId.isBlank()) {
            throw new IllegalArgumentException("preorderId 가 없다");
        }
    }
}
