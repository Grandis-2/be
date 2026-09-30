package com.grandis.nova.order.order.cancel;

/**
 * preorder 가 예약을 취소한 이유(PREORDER_CANCEL_REQUESTED 의 reason). 이름은 preorder 의 CancelReason 과 같아야 한다 —
 * 모르는 값이 오면 payload 를 풀지 못해 메시지가 DLQ 로 간다.
 *
 * 판정에 쓰는 것은 EXPIRY 하나다(결제된 주문이면 환불하지 않고 거절). 나머지는 이력 사유로만 남는다.
 */
public enum CancelReason {
    USER,
    ADMIN,
    /** 결제 기한 만료. */
    EXPIRY,
    /** 판매 중지. */
    CAMPAIGN_CANCELED
}
