package com.grandis.nova.batch.reconciliation.run;

/** 검사 실행의 상태. 대기 · 실행 중은 한 번에 하나뿐이다(reconciliation_runs.active_marker). */
public enum RunStatus {
    /** 관리자가 화면에서 요청했고 batch 가 아직 가져가지 않았다. 정기 실행은 이 상태를 거치지 않는다 */
    REQUESTED,
    RUNNING,
    COMPLETED,
    /** 판정 불가 — Mock 목록을 다 받지 못했거나 검사 중 오류, 또는 batch 재시작으로 중단 */
    FAILED
}
