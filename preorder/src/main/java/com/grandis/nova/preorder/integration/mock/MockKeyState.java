package com.grandis.nova.preorder.integration.mock;

/** 키로 조회한 그 키의 지금 상태(Mock docs/api.md 키로 등록 상태 조회) */
public enum MockKeyState {
    /** 활성 등록이 있다 */
    ACTIVE,
    /** 등록 후 취소됐거나 취소 표식만 있다(cancelMarkerAt 이 있다) */
    CANCELED,
    /** 등록 · 취소 표식 모두 없다(404 NOT_FOUND) */
    ABSENT
}
