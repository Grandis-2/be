package com.grandis.nova.preorder.query;

import com.grandis.nova.preorder.campaign.ShipmentBatchSnapshot;
import com.grandis.nova.preorder.preorder.PreorderHistoryEntry;
import com.grandis.nova.preorder.preorder.PreorderSnapshot;
import com.grandis.nova.preorder.syncjob.SyncAttempt;
import com.grandis.nova.preorder.syncjob.SyncJobSnapshot;
import com.grandis.nova.preorder.syncjob.SyncJobStatus;

import java.util.List;
import java.util.Map;

/** 조회 결과. 예약과 함께 화면이 필요로 하는 것(배송 차수 등)을 담는다. */
final class PreorderView {

    private PreorderView() {
    }

    public record Summary(PreorderSnapshot preorder, ShipmentBatchSnapshot shipmentBatch) {
    }

    /** 관리자 목록. 외부 등록 작업 상태가 더 붙는다(DEAD_LETTER 면 재처리 대기). */
    public record AdminSummary(PreorderSnapshot preorder, ShipmentBatchSnapshot shipmentBatch, SyncJobStatus registerJobStatus) {
    }

    /** 관리자 상세. 작업 · 시도 · 이력까지 한 번에 본다. */
    public record AdminDetail(PreorderSnapshot preorder, ShipmentBatchSnapshot shipmentBatch, List<SyncJobSnapshot> syncJobs,
                              Map<Long, List<SyncAttempt>> attempts, List<PreorderHistoryEntry> events) {
    }
}
