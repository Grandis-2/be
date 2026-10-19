package com.grandis.nova.preorder.campaign.api;

import com.grandis.nova.preorder.campaign.CampaignSchedule;

import java.time.Instant;
import java.util.UUID;

/** 회차 일정 한 건. 마감 · 다시 열기는 closesAt 을, 오픈 전 판매 중지는 opensAt 까지 바꾼다. */
record InternalCampaignResponse(UUID productId, Instant opensAt, Instant closesAt) {

    static InternalCampaignResponse from(CampaignSchedule schedule) {
        return new InternalCampaignResponse(schedule.productId(), schedule.opensAt(), schedule.closesAt());
    }
}
