package com.grandis.nova.preorder.campaign.api;

import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/** 마감한 접수를 다시 열 때의 새 마감 시각. 지금보다 뒤인지는 서비스가 본다. */
record ReopenCampaignRequest(
        @NotNull Instant closesAt
) {
}
