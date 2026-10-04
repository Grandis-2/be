package com.grandis.nova.payment.event;

/**
 * payment-events 큐로 받는 이벤트 종류. 여기 없는 종류는 받지 않는다 —
 * 조용히 버리면 계약이 어긋난 것을 모른 채 메시지를 잃는다.
 */
public enum InboundEventType {

    /** order: 결제된 주문을 취소 중으로 바꿨다. 그 주문의 결제를 전액 환불한다. */
    ORDER_REFUND_REQUESTED
}
