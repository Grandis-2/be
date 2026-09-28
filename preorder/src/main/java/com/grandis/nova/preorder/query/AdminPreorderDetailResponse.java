package com.grandis.nova.preorder.query;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.grandis.nova.preorder.preorder.PreorderSnapshot;
import com.grandis.nova.preorder.syncjob.SyncAttempt;
import com.grandis.nova.preorder.syncjob.SyncJobResponse;

import java.util.List;
import java.util.Map;

/** 관리자 상세(openapi AdminPreorderDetail). 입장권 · 내부 메모 · 작업 · 이력까지 본다. */
record AdminPreorderDetailResponse(
        @JsonUnwrapped PreorderDetailResponse detail,
        Long customerId,
        String admissionTicketId,
        String internalNote,
        List<SyncJobResponse> syncJobs,
        List<PreorderEventResponse> events
) {

    public static AdminPreorderDetailResponse from(PreorderView.AdminDetail view) {
        PreorderSnapshot preorder = view.preorder();
        PreorderView.Summary summary = new PreorderView.Summary(preorder, view.shipmentBatch());
        Map<Long, List<SyncAttempt>> attempts = view.attempts();
        return new AdminPreorderDetailResponse(PreorderDetailResponse.from(summary), preorder.customerId(),
                preorder.admissionTicketId(), preorder.internalNote(),
                view.syncJobs().stream()
                        .map(job -> SyncJobResponse.from(job, preorder.preorderToken(),
                                attempts.getOrDefault(job.id(), List.of())))
                        .toList(),
                view.events().stream().map(PreorderEventResponse::from).toList());
    }
}
