package com.grandis.nova.order.order.cancel;

import com.grandis.nova.order.order.domain.enums.OrderStatus;

/**
 * 지금은 정리 결과를 정할 수 없다 — 승인 결과를 기다리거나(AUTHORIZING), 환불이 필요하거나 진행 중이다(결제 작업 몫).
 *
 * 결과를 적지 않고 트랜잭션을 되돌린다. 소비기는 메시지를 지우지 않으므로 나중에 다시 받고, 끝내 못 정하면 DLQ 로 간다.
 * 코드 버그(IllegalStateException)와 구분하려고 따로 둔다.
 */
public class SettlementDeferredException extends RuntimeException {

    public SettlementDeferredException(Long preorderInternalId, OrderStatus status, CancelReason reason) {
        super("주문 정리 결과를 아직 정할 수 없다: preorderInternalId=%d, status=%s, reason=%s"
                .formatted(preorderInternalId, status, reason));
    }
}
