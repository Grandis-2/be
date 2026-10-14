package com.grandis.nova.preorder.campaign.application;

import com.grandis.nova.preorder.campaign.CampaignSchedule;
import com.grandis.nova.preorder.campaign.ShipmentBatchSnapshot;
import com.grandis.nova.preorder.campaign.domain.PreorderSaleStatus;

import java.time.Instant;

/**
 * 접수 현황을 만드는 재료. 캐시에는 이것을 두고 접수 상태는 물을 때의 시각으로 정한다 —
 * 상태까지 두면 오픈 · 마감 순간에 캐시 시간만큼 옛 상태가 보인다.
 *
 * @param nextBatch 다음 순번이 속한 차수. 차수가 아직 없으면 null
 * @param readAt    DB 에서 읽은 시각
 */
record CampaignProgressSource(CampaignSchedule schedule, ShipmentBatchSnapshot nextBatch, Instant readAt) {

    CampaignProgress at(Instant now) {
        PreorderSaleStatus saleStatus = PreorderSaleStatus.of(schedule.opensAt(), schedule.closesAt(), now);
        return new CampaignProgress(schedule.productId(), saleStatus,
                saleStatus == PreorderSaleStatus.OPEN ? nextBatch : null, readAt);
    }
}
