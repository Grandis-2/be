package com.grandis.nova.preorder.query;

import com.grandis.nova.preorder.campaign.ShipmentBatch;
import com.grandis.nova.preorder.preorder.Preorder;
import com.grandis.nova.preorder.preorder.PreorderEvent;
import com.grandis.nova.preorder.syncjob.PreorderSyncJob;
import com.grandis.nova.preorder.syncjob.SyncAttempt;
import com.grandis.nova.preorder.syncjob.SyncJobStatus;

import java.util.List;
import java.util.Map;

/** 조회 결과. 예약과 함께 화면이 필요로 하는 것(배송 차수 등)을 담는다. */
final class PreorderView {

    private PreorderView() {
    }

    public record Summary(Preorder preorder, ShipmentBatch shipmentBatch) {
    }

    /** 관리자 목록. 외부 등록 작업 상태가 더 붙는다(DEAD_LETTER 면 재처리 대기). */
    public record AdminSummary(Preorder preorder, ShipmentBatch shipmentBatch, SyncJobStatus registerJobStatus) {
    }

    /** 관리자 상세. 작업 · 시도 · 이력까지 한 번에 본다. */
    public record AdminDetail(Preorder preorder, ShipmentBatch shipmentBatch, List<PreorderSyncJob> syncJobs,
                              Map<Long, List<SyncAttempt>> attempts, List<PreorderEvent> events) {
    }
}
