package com.grandis.nova.preorder.query.application;

/**
 * 화면에 보이는 예약 단계. 저장하지 않고 조회 때 원장 상태 · 등록 작업 상태 · 결제 기한 · 결제 시작 시각으로 정한다 —
 * 판단(만료 · 취소 · 결제 가능)은 원장 상태만 본다. 문구 · 버튼은 이 값으로 고른다.
 */
public enum PreorderDisplayStatus {
    /** 접수됨. 외부 등록을 아직 시도하지 않았다. */
    RECEIVED,
    /** 외부 등록 시도 · 재시도 중(관리자 재처리 대기 포함). */
    PROCESSING,
    /** 외부 등록 확인 — 결제 기한 안. */
    PAYABLE,
    /** 주문이 만들어져 결제가 진행 중이다(표시만, 판단에 쓰지 않음). */
    PAYMENT_IN_PROGRESS,
    /** 결제 기한이 지났다(만료 처리 전). */
    PAYMENT_EXPIRED,
    /** 결제 확인 = 예약 확정. */
    RESERVED,
    CANCELING,
    CANCELED
}
