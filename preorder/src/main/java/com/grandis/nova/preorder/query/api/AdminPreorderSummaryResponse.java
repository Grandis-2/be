package com.grandis.nova.preorder.query.api;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.grandis.nova.preorder.query.application.PreorderView;
import com.grandis.nova.preorder.syncjob.SyncJobStatus;

import java.util.UUID;

/**
 * 관리자 목록 한 줄(openapi AdminPreorderSummary). 회원과 외부 등록 작업이 더 붙는다.
 *
 * @param customerName      회원 이름(member 가 가린 값, 예: 김**). member 에게 받지 못했으면 null
 * @param registerSyncJobId 외부 등록 작업 id — 일괄 재처리에 바로 넘긴다. 작업이 없으면 null
 */
record AdminPreorderSummaryResponse(
        @JsonUnwrapped PreorderSummaryResponse summary,
        UUID customerId,
        String customerName,
        UUID registerSyncJobId,
        SyncJobStatus registerJobStatus
) {

    public static AdminPreorderSummaryResponse from(PreorderView.AdminSummary view, String customerName) {
        return new AdminPreorderSummaryResponse(
                PreorderSummaryResponse.from(
                        new PreorderView.Summary(view.preorder(), view.shipmentBatch(), view.displayStatus()), null),   // 관리자 목록은 사진을 싣지 않는다
                view.preorder().customerId(), customerName,
                view.registerJob() == null ? null : view.registerJob().id(),
                view.registerJob() == null ? null : view.registerJob().status());
    }
}
