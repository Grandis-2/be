package com.grandis.nova.payment.domain.enums;

/**
 * 결제가 거절된 까닭을 사용자에게 보일 만큼만 나눈 것. 결제사 원본 코드 · 문구는 대상 서비스 · 프론트로 내보내지 않는다 —
 * 원본은 거래 행(last_error_code · last_error_message)에만 남는다.
 */
public enum DeclineReason {
    /** 카드 · 계좌 · 구매자 사유(한도 초과 · 정지 카드 · 비밀번호 오류 등). 다른 결제 수단으로 다시 할 수 있다. */
    CARD_REJECTED,
    /** 결제창 인증이 만료됐다(인증 뒤 10분). 결제창을 다시 연다. */
    PAYMENT_EXPIRED,
    /** 그 밖(잘못된 요청 · 상점 설정 오류). 사용자가 바꿀 수 있는 것이 없다. */
    FAILED
}
