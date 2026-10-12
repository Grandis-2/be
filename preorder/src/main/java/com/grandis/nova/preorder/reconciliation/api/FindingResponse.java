package com.grandis.nova.preorder.reconciliation.api;

import com.fasterxml.jackson.annotation.JsonRawValue;
import com.grandis.nova.preorder.reconciliation.domain.ActionStatus;
import com.grandis.nova.preorder.reconciliation.domain.ActionType;
import com.grandis.nova.preorder.reconciliation.domain.FindingRow;

import java.time.Instant;
import java.util.UUID;

/**
 * 불일치 한 건. 화면의 불일치 상세 표와 상세보기.
 *
 * @param targetKey       예약 · Mock 건은 외부 키(관리자 예약 상세를 이 값으로 연다), 순번 빈틈은 상품 id
 * @param beSnapshot      검사 때 본 be 값(객체). be 에 없는 건이면 null
 * @param mockSnapshot    검사 때 본 Mock 원장 행(객체). Mock 에 없거나 be 내부 검사면 null
 * @param firstDetectedAt 이 (코드, 대상)을 처음 발견한 시각 — 여러 실행에 걸쳐 남은 불일치를 일시적인 것과 가른다
 * @param actionType      한 조치. 조치 전이면 null
 */
record FindingResponse(UUID findingId, UUID runId, String checkCode, String targetKey,
                       @JsonRawValue String beSnapshot, @JsonRawValue String mockSnapshot, Instant firstDetectedAt,
                       ActionStatus actionStatus, ActionType actionType, String actionRequestedBy,
                       Instant actionRequestedAt, String actionNote) {

    static FindingResponse from(FindingRow finding) {
        return new FindingResponse(finding.id(), finding.runId(), finding.checkCode(), finding.targetKey(),
                finding.beSnapshot(), finding.mockSnapshot(), finding.firstDetectedAt(), finding.actionStatus(),
                finding.actionType(), finding.actionRequestedBy(), finding.actionRequestedAt(), finding.actionNote());
    }
}
