package com.grandis.nova.preorder.preorder;

/** 취소를 시작한 까닭. order 는 EXPIRY 일 때 결제된 주문을 환불하지 않고 거절로 돌려준다. */
public enum CancelReason {
    USER,
    ADMIN,
    EXPIRY,
    CAMPAIGN_CANCELED
}
