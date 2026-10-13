package com.grandis.nova.preorder.reconciliation.domain;

import java.util.Set;

/**
 * 불일치 건에 한 조치(reconciliation_findings.action_type)와 그 조치를 할 수 있는 확인할 것 코드(구현-조치 2장 · Figma 5장).
 * 코드에 맞는 수단이 없으면 수동 조사다. 재고 보정은 아직 받지 않는다(값만 읽는다).
 */
public enum ActionType {

    /**
     * 키로 Mock 취소 — Mock 쪽만 고친다. be 예약이 이미 취소 완료이거나(앞의 셋) 그 키의 예약이 없을 때(Mock 에만 있음)만 쓴다.
     * Mock 에만 있음은 be 기록이 사라진 경우일 수 있어 일괄로 받지 않는다
     */
    EXTERNAL_CANCEL(Set.of("CANCELED_BUT_MOCK_ACTIVE", "CANCELED_WITHOUT_MOCK_MARKER", "CANCEL_JOB_INCOMPLETE",
            "MOCK_ONLY")),
    /** 등록 작업 재처리 — Mock 은 등록됐는데 be 는 등록 대기 · DEAD_LETTER. 같은 키라 같은 번호로 맞춰진다 */
    REPROCESS(Set.of("PENDING_BUT_MOCK_ACTIVE")),
    /**
     * 관리자 예약 취소 — Mock 에서 취소된 키는 되살릴 수 없다(같은 키로 다시 등록하면 409). 등록 대기인데 취소 표식이 있는 예약은
     * "등록 실패를 사유로 취소하지 않는다" 의 예외다(10-12)
     */
    ADMIN_CANCEL(Set.of("REGISTERED_BUT_MOCK_CANCELED", "PENDING_BUT_MOCK_CANCEL_MARKER")),
    /** 재고 보정 — 아직 받지 않는다 */
    STOCK_ADJUST(Set.of());

    private final Set<String> checkCodes;

    ActionType(Set<String> checkCodes) {
        this.checkCodes = checkCodes;
    }

    public boolean allows(String checkCode) {
        return checkCodes.contains(checkCode);
    }
}
