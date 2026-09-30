package com.grandis.nova.order.event;

/**
 * order-events 큐로 받는 이벤트 종류. 여기 없는 종류는 받지 않는다 —
 * 조용히 버리면 계약이 어긋난 것을 모른 채 메시지를 잃는다.
 *
 * common:outbox 이전 시: preorder event.InboundEventType 과 같은 모양이다. 종류 목록은 모듈마다 달라 모듈에 남긴다.
 */
public enum InboundEventType {

    /** preorder: 예약 취소를 시작했다. 주문을 정리하고 결과를 돌려준다. */
    PREORDER_CANCEL_REQUESTED
}
