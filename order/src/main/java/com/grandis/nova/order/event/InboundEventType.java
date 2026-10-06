package com.grandis.nova.order.event;

/**
 * order-events 큐로 받는 이벤트 종류. 여기 없는 종류는 받지 않는다 —
 * 조용히 버리면 계약이 어긋난 것을 모른 채 메시지를 잃는다.
 */
public enum InboundEventType {

    /** preorder: 예약 취소를 시작했다. 주문을 정리하고 결과를 돌려준다. */
    PREORDER_CANCEL_REQUESTED,
    /** payment: 주문 결제가 확정됐다(승인 · 거절). 승인 API 의 동기 응답과 같은 전이를 멱등으로 반영한다. */
    ORDER_PAYMENT_SETTLED,
    /** payment: 취소 중인 주문의 환불이 확정됐다(완료 · 실패). */
    ORDER_REFUND_SETTLED
}
