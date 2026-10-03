package com.grandis.nova.order.client.payment;

/**
 * 결제가 거절된 까닭(payment domain.enums.DeclineReason 과 이름이 같아야 한다). 프론트에 그대로 내보낸다 —
 * 결제사 원본 코드 · 문구는 payment 밖으로 나오지 않는다.
 */
public enum DeclineReason {
    /** 카드 · 계좌 · 구매자 사유. 다른 결제 수단으로 다시 할 수 있다. */
    CARD_REJECTED,
    /** 결제창 인증이 만료됐다. 결제창을 다시 연다. */
    PAYMENT_EXPIRED,
    /** 그 밖(잘못된 요청 · 상점 설정 오류). */
    FAILED
}
