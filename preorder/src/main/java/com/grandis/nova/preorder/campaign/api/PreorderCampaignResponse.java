package com.grandis.nova.preorder.campaign.api;

import com.grandis.nova.preorder.campaign.domain.PreorderCampaign;
import com.grandis.nova.preorder.campaign.domain.PreorderSaleStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * 모집 일정(openapi AdminPreorderCampaign). 순번 카운터 대신 발급 수만 알린다.
 *
 * @param saleStatus 저장하지 않고 일정과 서버 시각으로 계산한다
 * @param issuedCount 지금까지 발급한 순번 수(취소 행 포함)
 * @param expectedVisitors 관리자가 넣은 예상 방문자 수. 넣지 않았으면 null
 * @param openAlertSubscribers 오픈 알림 신청 수 — 예상 방문자 수를 정할 때 참고한다
 */
record PreorderCampaignResponse(
        UUID productId,
        Instant opensAt,
        Instant closesAt,
        PreorderSaleStatus saleStatus,
        long issuedCount,
        Instant openNotifiedAt,
        Integer expectedVisitors,
        long openAlertSubscribers
) {

    public static PreorderCampaignResponse from(PreorderCampaign campaign, long openAlertSubscribers, Instant now) {
        return new PreorderCampaignResponse(campaign.getProductId(), campaign.getOpensAt(), campaign.getClosesAt(),
                campaign.saleStatus(now), campaign.issuedCount(), campaign.getOpenNotifiedAt(),
                campaign.getExpectedVisitors(), openAlertSubscribers);
    }
}
