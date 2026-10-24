package com.grandis.nova.order.order.domain.enums;

/**
 * 주문 상태를 움직이는 사건. 호출하는 쪽은 "다음 상태" 가 아니라 "무슨 일이 일어났는가" 를 알린다.
 * 다음 상태는 {@link OrderStatus#next} 가 정한다.
 *
 * 취소 이유(USER · ADMIN · EXPIRY · CAMPAIGN_CANCELED)는 사건에 섞지 않는다. 이유에 따른 판단
 * (예: 만료인데 이미 결제됐으면 거절)은 유스케이스가 먼저 하고, 이유는 이력의 reason 으로 남긴다.
 */
public enum OrderTrigger {

    /** 예약 취소 수신 · (나중에) 사용자 취소. */
    CANCEL_REQUESTED,
    /** 결제를 요청했다. */
    PAYMENT_REQUESTED,
    /** 결제가 승인됐다. */
    PAYMENT_APPROVED,
    /** 결제가 거절됐다. 다시 결제할 수 있다. */
    PAYMENT_DECLINED,
    /** 장바구니 · 바로 구매: 생성 뒤 정한 시간(20분)이 지나도 결제 결과를 몰라 기다리기를 멈춘다. 늦게 승인되면 자동 환불한다. */
    PAYMENT_UNCONFIRMED,
    /** 결제된 주문의 환불이 끝났다. */
    REFUND_COMPLETED,
    /** 관리자: 상품 준비 시작. */
    PREPARATION_STARTED,
    /** 관리자: 포장 완료. */
    PACKED,
    /** 관리자: 출고. */
    SHIPPED,
    /** 관리자: 배송 완료. */
    DELIVERED
}
