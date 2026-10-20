package com.grandis.nova.preorder.campaign.api;

import java.time.Instant;
import java.util.List;

/** @param serverTime 응답을 만든 preorder 시각. 받는 쪽이 시계 차이를 확인한다 */
record OpenCampaignsResponse(List<InternalCampaignResponse> items, Instant serverTime) {
}
