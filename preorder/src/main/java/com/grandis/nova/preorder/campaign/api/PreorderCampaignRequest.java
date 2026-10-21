package com.grandis.nova.preorder.campaign.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Instant;

/**
 * 모집 일정 설정. closesAt 이 opensAt 보다 뒤인지는 서비스가 본다.
 *
 * @param expectedVisitors 예상 방문자 수(선택). 빼면 그대로 둔다 — 이 칸을 모르는 화면이 저장해도 지워지지 않게
 */
record PreorderCampaignRequest(
        @NotNull Instant opensAt,
        @NotNull Instant closesAt,
        @Positive @Max(MAX_EXPECTED_VISITORS) Integer expectedVisitors
) {

    /** 칸(int)에 넉넉히 들어가는 상한. 이보다 크면 잘못 넣은 값이다. */
    static final long MAX_EXPECTED_VISITORS = 100_000_000;
}
