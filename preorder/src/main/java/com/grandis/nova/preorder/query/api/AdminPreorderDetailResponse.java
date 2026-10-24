package com.grandis.nova.preorder.query.api;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.grandis.nova.preorder.preorder.PreorderSnapshot;
import com.grandis.nova.preorder.query.application.PreorderView;
import com.grandis.nova.preorder.syncjob.SyncAttempt;
import com.grandis.nova.preorder.syncjob.SyncJobResponse;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 관리자 상세(openapi AdminPreorderDetail). 입장권 · 내부 메모 · 작업 · 이력까지 본다.
 *
 * @param customerName 회원 이름(member 가 가린 값, 예: 김**). member 에게 받지 못했으면 null
 */
record AdminPreorderDetailResponse(
        @JsonUnwrapped PreorderDetailResponse detail,
        UUID customerId,
        String customerName,
        String admissionTicketId,
        String internalNote,
        List<SyncJobResponse> syncJobs,
        List<PreorderEventResponse> events
) {

    public static AdminPreorderDetailResponse from(PreorderView.AdminDetail view, String customerName) {
        PreorderSnapshot preorder = view.preorder();
        PreorderView.Summary summary = new PreorderView.Summary(preorder, view.shipmentBatch(), view.displayStatus());
        Map<UUID, List<SyncAttempt>> attempts = view.attempts();
        return new AdminPreorderDetailResponse(PreorderDetailResponse.from(summary, null), preorder.customerId(), customerName,   // 관리자 상세는 사진을 싣지 않는다
                preorder.admissionTicketId(), preorder.internalNote(),
                view.syncJobs().stream()
                        .map(job -> SyncJobResponse.from(job, preorder.preorderToken(),
                                attempts.getOrDefault(job.id(), List.of())))
                        .toList(),
                view.events().stream().map(PreorderEventResponse::from).toList());
    }
}
