package com.grandis.nova.preorder.preorder;

/** 이력을 남긴 주체. 관리자는 회원 밖 단일 계정이라 회원 참조를 두지 않는다. */
public enum EventActor {
    USER,
    ADMIN,
    SYSTEM;

    /** 관리자 전이는 사유가 필요하다(DB CHECK ck_preorder_event_admin_reason 과 같은 규칙). */
    void requireReason(String reason) {
        if (this == ADMIN && (reason == null || reason.isBlank())) {
            throw new IllegalArgumentException("관리자 전이는 사유가 필요하다");
        }
    }
}
