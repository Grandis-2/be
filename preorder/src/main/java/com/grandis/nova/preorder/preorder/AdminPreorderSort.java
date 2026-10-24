package com.grandis.nova.preorder.preorder;

/** 관리자 예약 목록의 순서. 값이 같으면 예약 id 로 한 번 더 정해 쪽을 넘겨도 겹치거나 빠지지 않는다. */
public enum AdminPreorderSort {
    /** 최신 접수순. */
    NEWEST,
    /** 오래된 접수순 — 오래 막힌 외부 등록 실패부터 재처리할 때. */
    OLDEST,
    /** 순번순. 순번은 상품마다 따로 매기므로 상품과 함께 준다. */
    QUEUE_POSITION,
    /** 결제 기한 임박순. 기한은 결제를 기다리는(REGISTERED) 예약에만 있어 나머지는 뒤에 둔다. */
    PAYMENT_DUE
}
