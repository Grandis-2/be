package com.grandis.nova.preorder.syncjob;

/** 외부 호출을 일으킨 주체. 접수 · 취소로 생긴 작업은 SYSTEM, 관리자 재처리로 다시 보낸 작업은 ADMIN 이다. */
public enum SyncActor {
    SYSTEM,
    ADMIN
}
