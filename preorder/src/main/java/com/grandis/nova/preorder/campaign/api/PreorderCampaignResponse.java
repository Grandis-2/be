package com.grandis.nova.preorder.campaign.api;

import com.grandis.nova.preorder.campaign.domain.PreorderCampaign;
import com.grandis.nova.preorder.campaign.domain.PreorderSaleStatus;

import java.time.Instant;

/**
 * 모집 일정(openapi AdminPreorderCampaign). 순번 카운터 대신 발급 수만 알린다.
 *
 * @param saleStatus 저장하지 않고 일정과 서버 시각으로 계산한다
 * @param issuedCount 지금까지 발급한 순번 수(취소 행 포함)
 */
record PreorderCampaignResponse(
        Long productId,
        Instant opensAt,
        Instant closesAt,
        PreorderSaleStatus saleStatus,
        long issuedCount,
        Instant openNotifiedAt
) {

    public static PreorderCampaignResponse from(PreorderCampaign campaign, Instant now) {
        return new PreorderCampaignResponse(campaign.getProductId(), campaign.getOpensAt(), campaign.getClosesAt(),
                PreorderSaleStatus.of(campaign, now), campaign.issuedCount(), campaign.getOpenNotifiedAt());
    }
}
