package com.grandis.nova.preorder.reconciliation.domain;

/**
 * 불일치 건의 조치 상태(reconciliation_findings.action_status). batch 는 NOT_STARTED 로만 넣는다.
 * 재처리 · 관리자 예약 취소는 worker 가 나중에 처리해 REQUESTED 로 남고, 다음 검사에서 그 건이 사라지면 끝난 것이다.
 */
public enum ActionStatus {
    NOT_STARTED,
    REQUESTED,
    SUCCEEDED,
    ALREADY_RESOLVED,
    FAILED
}
