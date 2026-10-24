package com.grandis.nova.payment.event;

import java.util.Arrays;

/**
 * payment-events 큐로 받는 이벤트 종류. 여기 없는 종류는 받지 않는다 —
 * 조용히 버리면 계약이 어긋난 것을 모른 채 메시지를 잃는다.
 */
public enum InboundEventType {

    /** order: 결제된 주문을 취소 중으로 바꿨다. 그 주문의 결제를 전액 환불한다. */
    ORDER_REFUND_REQUESTED;

    /** 주문 이벤트의 aggregate. aggregateId 가 주문 id 라는 뜻이다(받는 종류는 모두 주문 이벤트다). */
    public static final String ORDER_AGGREGATE = "ORDER";

    /** 이 이름의 이벤트를 받는가. DLQ 적재 · 되돌리기 전 검사가 쓴다. */
    public static boolean isKnown(String eventType) {
        return eventType != null && Arrays.stream(values()).anyMatch(type -> type.name().equals(eventType));
    }
}
