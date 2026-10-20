package com.grandis.nova.catalog.listing;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** preorder 소유 회차의 시각 둘 — preorder 회차 일정 API 로 받는다(PreorderCampaigns). */
public record CampaignWindow(Instant opensAt, Instant closesAt) {

    public PreorderSaleStatus statusAt(Instant now) {
        return PreorderSaleStatus.of(opensAt, closesAt, now);
    }

    /** 지금 접수 중인 회차의 상품 id — 회원 목록의 노출 조건(오픈 시각 이상 · 마감 시각 미만, {@link PreorderSaleStatus#OPEN}). */
    public static Set<UUID> acceptingAt(Map<UUID, CampaignWindow> windows, Instant now) {
        return windows.entrySet().stream().filter(entry -> entry.getValue().statusAt(now) == PreorderSaleStatus.OPEN)
                .map(Map.Entry::getKey).collect(Collectors.toUnmodifiableSet());
    }
}
