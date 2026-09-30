package com.grandis.nova.preorder.campaign;

import com.grandis.nova.preorder.campaign.domain.PreorderCampaign;

import java.time.Instant;

/** 모집 일정. [opensAt, closesAt) 동안 접수한다. */
public record CampaignSchedule(Long productId, Instant opensAt, Instant closesAt) {

    public static CampaignSchedule of(PreorderCampaign campaign) {
        return new CampaignSchedule(campaign.getProductId(), campaign.getOpensAt(), campaign.getClosesAt());
    }
}
