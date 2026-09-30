package com.grandis.nova.preorder.event;

import java.util.Arrays;

/**
 * preorder-events 큐로 받는 이벤트 종류(계약 2.1). 여기 없는 종류는 받지 않는다 —
 * 조용히 버리면 계약이 어긋난 것을 모른 채 메시지를 잃는다.
 */
public enum InboundEventType {

    /** worker: 외부 등록 · 취소 성공. */
    EXTERNAL_JOB_SUCCEEDED,
    /** order: 예약 취소에 따른 주문 정리 결과. */
    PREORDER_ORDER_SETTLED,
    /** batch: 결제 기한 만료. */
    PREORDER_EXPIRY_REQUESTED,
    /** catalog: 회차 판매 중지. */
    PREORDER_CAMPAIGN_CANCELED;

    /** 이 이름의 이벤트를 받는가. DLQ 적재 · 되돌리기 전 검사가 쓴다. */
    public static boolean isKnown(String eventType) {
        return eventType != null && Arrays.stream(values()).anyMatch(type -> type.name().equals(eventType));
    }
}
