package com.grandis.nova.batch.reconciliation.run;

/**
 * 건별 행 없이 세기만 하는 수. 화면의 판정 보류 · 미완료 현황이다.
 *
 * @param held        판정 보류 — 기준 시각 뒤에 바뀐 키
 * @param pendingSync 처리 중 — 등록 대기(등록 작업이 DEAD_LETTER 가 아님)
 * @param canceling   처리 중 — 취소 중
 * @param deadLetter  관리자 대기 — 등록 대기인데 등록 작업이 DEAD_LETTER
 */
public record RunCounts(int held, int pendingSync, int canceling, int deadLetter) {

    public static final RunCounts ZERO = new RunCounts(0, 0, 0, 0);
}
