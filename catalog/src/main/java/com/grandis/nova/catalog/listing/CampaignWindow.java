package com.grandis.nova.catalog.listing;

import java.time.Instant;

/** preorder 소유 회차의 시각 둘. catalog 는 이것만 읽는다. */
public record CampaignWindow(Instant opensAt, Instant closesAt) {

    public PreorderSaleStatus statusAt(Instant now) {
        return PreorderSaleStatus.of(opensAt, closesAt, now);
    }
}
