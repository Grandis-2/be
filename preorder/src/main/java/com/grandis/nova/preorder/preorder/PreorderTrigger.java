package com.grandis.nova.preorder.preorder;

/**
 * 예약 상태를 움직이는 사건. 호출하는 쪽은 "다음 상태" 가 아니라 "무슨 일이 일어났는가" 를 알린다.
 * 다음 상태는 {@link PreorderStatus#next} 가 정한다.
 */
public enum PreorderTrigger {

    /** 외부 등록이 확인됐다(worker 의 EXTERNAL_JOB_SUCCEEDED, REGISTER). */
    REGISTER_CONFIRMED,
    /** 사용자 · 관리자 · 만료 · 회차 취소로 취소를 시작한다. */
    CANCEL_REQUESTED,
    /** 주문 쪽이 취소를 거절했다(배송 시작). PAYABLE 에서 시작한 취소에만 온다. */
    CANCEL_REJECTED,
    /** 외부 취소와 (있으면) 주문 취소가 모두 끝났다. */
    CANCEL_COMPLETED
}
