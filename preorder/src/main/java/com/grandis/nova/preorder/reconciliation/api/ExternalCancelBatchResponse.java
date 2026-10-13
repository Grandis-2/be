package com.grandis.nova.preorder.reconciliation.api;

import com.grandis.nova.preorder.reconciliation.application.ExternalCancelOutcome;
import java.util.List;
import java.util.UUID;

/** 일괄 키로 Mock 취소의 건별 결과. 넣은 순서대로다 */
record ExternalCancelBatchResponse(List<Item> items) {

    static ExternalCancelBatchResponse from(List<ExternalCancelOutcome> outcomes) {
        return new ExternalCancelBatchResponse(outcomes.stream().map(Item::from).toList());
    }

    /**
     * @param finding   조치한 뒤의 건(조치 상태 SUCCEEDED · ALREADY_RESOLVED · FAILED). 거절됐으면 null
     * @param errorCode 조치를 시작하지 못한 사유. 조치했으면 null
     */
    record Item(UUID findingId, FindingResponse finding, String errorCode, String errorMessage) {

        static Item from(ExternalCancelOutcome outcome) {
            return new Item(outcome.findingId(),
                    outcome.finding() == null ? null : FindingResponse.from(outcome.finding()),
                    outcome.errorCode(), outcome.errorMessage());
        }
    }
}
