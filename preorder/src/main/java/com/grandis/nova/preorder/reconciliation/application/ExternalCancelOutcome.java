package com.grandis.nova.preorder.reconciliation.application;

import java.util.UUID;

/**
 * 일괄 키로 Mock 취소의 한 건. 조치를 시작했으면 finding 에 결과(SUCCEEDED · ALREADY_RESOLVED · FAILED)가, 시작하지 못했으면 오류가 있다.
 *
 * @param finding   조치한 뒤의 건. 거절됐으면 null
 * @param errorCode 거절 사유(RECONCILIATION_ACTION_NOT_ALLOWED 등). 조치했으면 null
 */
public record ExternalCancelOutcome(UUID findingId, FindingView finding, String errorCode, String errorMessage) {

    static ExternalCancelOutcome done(FindingView finding) {
        return new ExternalCancelOutcome(finding.finding().id(), finding, null, null);
    }

    static ExternalCancelOutcome rejected(UUID findingId, String errorCode, String errorMessage) {
        return new ExternalCancelOutcome(findingId, null, errorCode, errorMessage);
    }
}
