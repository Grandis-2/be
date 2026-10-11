package com.grandis.nova.preorder.query.api;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.grandis.nova.preorder.query.application.PreorderView;
import com.grandis.nova.preorder.syncjob.SyncJobStatus;

import java.util.UUID;

/** 관리자 목록 한 줄(openapi AdminPreorderSummary). 회원과 외부 등록 작업 상태가 더 붙는다. */
record AdminPreorderSummaryResponse(
        @JsonUnwrapped PreorderSummaryResponse summary,
        UUID customerId,
        SyncJobStatus registerJobStatus
) {

    public static AdminPreorderSummaryResponse from(PreorderView.AdminSummary view) {
        return new AdminPreorderSummaryResponse(
                PreorderSummaryResponse.from(
                        new PreorderView.Summary(view.preorder(), view.shipmentBatch(), view.displayStatus())),
                view.preorder().customerId(), view.registerJobStatus());
    }
}
