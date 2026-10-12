package com.grandis.nova.preorder.reconciliation.domain;

/** 검사 실행 상태(reconciliation_runs.status). preorder 는 REQUESTED 로만 넣고 나머지는 batch 가 바꾼다. */
public enum RunStatus {
    REQUESTED,
    RUNNING,
    COMPLETED,
    FAILED
}
