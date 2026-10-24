package com.grandis.nova.preorder.query.application;

import com.grandis.nova.preorder.campaign.ShipmentBatchSnapshot;
import com.grandis.nova.preorder.preorder.PreorderHistoryEntry;
import com.grandis.nova.preorder.preorder.PreorderSnapshot;
import com.grandis.nova.preorder.syncjob.SyncAttempt;
import com.grandis.nova.preorder.syncjob.SyncJobSnapshot;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 조회 결과. 예약과 함께 화면이 필요로 하는 것(배송 차수 등)을 담는다. */
public final class PreorderView {

    private PreorderView() {
    }

    public record Summary(PreorderSnapshot preorder, ShipmentBatchSnapshot shipmentBatch,
                          PreorderDisplayStatus displayStatus) {
    }

    /** 진행 중인 예약 한 건. 상품 목록에서 예약 여부 · 단계만 보이는 데 쓴다. */
    public record Active(PreorderSnapshot preorder, PreorderDisplayStatus displayStatus) {
    }

    /** 관리자 목록. 외부 등록 작업이 더 붙는다(DEAD_LETTER 면 재처리 대기). 작업이 없으면 null 이다. */
    public record AdminSummary(PreorderSnapshot preorder, ShipmentBatchSnapshot shipmentBatch, SyncJobSnapshot registerJob,
                               PreorderDisplayStatus displayStatus) {
    }

    /** 관리자 상세. 작업 · 시도 · 이력까지 한 번에 본다. */
    public record AdminDetail(PreorderSnapshot preorder, ShipmentBatchSnapshot shipmentBatch, List<SyncJobSnapshot> syncJobs,
                              Map<UUID, List<SyncAttempt>> attempts, List<PreorderHistoryEntry> events,
                              PreorderDisplayStatus displayStatus) {
    }
}
