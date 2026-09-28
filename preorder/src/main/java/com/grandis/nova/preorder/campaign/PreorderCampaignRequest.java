package com.grandis.nova.preorder.campaign;

import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/** 모집 일정 설정. closesAt 이 opensAt 보다 뒤인지는 서비스가 본다. */
public record PreorderCampaignRequest(
        @NotNull Instant opensAt,
        @NotNull Instant closesAt
) {
}
