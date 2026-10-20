package com.grandis.nova.preorder.campaign.api;

import com.grandis.nova.preorder.campaign.ShipmentBatchResponse;
import com.grandis.nova.preorder.campaign.application.CampaignProgress;
import com.grandis.nova.preorder.campaign.domain.PreorderSaleStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * 상품의 접수 현황. 접수 수는 싣지 않는다 — 예약 규모를 밖에 알리지 않는다.
 *
 * @param nextBatch 지금 신청하면 배정될 배송 차수. 오픈 전 · 마감 뒤에는 null. 실제 배정은 접수 응답이 알려 준다
 * @param countedAt 일정 · 차수를 읽은 시각. 잠깐 캐시한 값이라 지금보다 몇 초 이전일 수 있다(접수 상태는 지금 시각 기준이다)
 */
public record CampaignProgressResponse(
        UUID productId,
        PreorderSaleStatus saleStatus,
        ShipmentBatchResponse nextBatch,
        Instant countedAt
) {

    public static CampaignProgressResponse from(CampaignProgress progress) {
        return new CampaignProgressResponse(progress.productId(), progress.saleStatus(),
                progress.nextBatch() == null ? null : ShipmentBatchResponse.from(progress.nextBatch()),
                progress.countedAt());
    }
}
