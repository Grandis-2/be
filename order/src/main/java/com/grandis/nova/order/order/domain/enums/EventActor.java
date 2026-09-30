package com.grandis.nova.order.order.domain.enums;

/**
 * 이력을 남긴 주체(ck_order_event_actor). 관리자는 회원 밖 단일 계정이라 회원 참조를 두지 않는다.
 * preorder 에 같은 이름의 enum 이 있지만 common 으로 올리지 않는다 — 서비스 모듈끼리 묶이지 않게.
 */
public enum EventActor {
    USER,
    ADMIN,
    SYSTEM
}
