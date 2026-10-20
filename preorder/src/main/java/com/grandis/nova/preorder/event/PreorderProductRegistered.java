package com.grandis.nova.preorder.event;

import com.grandis.nova.preorder.campaign.CampaignRegistration;
import com.grandis.nova.preorder.campaign.ProductListing;
import com.grandis.nova.preorder.campaign.ProductStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * catalog 가 사전예약 상품을 등록했다. 회차 · 차수 값의 원장이 catalog 에 없어 payload 에 실려 온다.
 * 상품 id 는 봉투의 aggregateId 다. 상품명 · 판매 상태 · 공개 여부 칸이 없으면(이 칸을 모르는 catalog) 상품명 · 상태는 비우고
 * 공개 · 번호 0 으로 본다 — 다음 변경 이벤트가 이긴다.
 */
record PreorderProductRegistered(Campaign campaign, List<ShipmentBatch> shipmentBatches, String title, String status,
                                 Boolean visible, Long listingVersion) {

    /** @throws IllegalArgumentException 회차 · 차수가 없거나 모르는 판매 상태 — 재시도 뒤 DLQ 로 간다 */
    CampaignRegistration toRegistration() {
        if (campaign == null || shipmentBatches == null) {
            throw new IllegalArgumentException("회차 또는 배송 차수가 없는 상품 등록 이벤트");
        }
        return new CampaignRegistration(campaign.opensAt(), campaign.closesAt(), shipmentBatches.stream()
                .map(batch -> new CampaignRegistration.Batch(batch.batchNumber(), batch.positionFrom(),
                        batch.positionTo(), batch.estimatedShipStart(), batch.estimatedShipEnd()))
                .toList(), new ProductListing(title, status == null ? null : ProductStatus.valueOf(status),
                !Boolean.FALSE.equals(visible), listingVersion == null ? 0 : listingVersion));
    }

    record Campaign(Instant opensAt, Instant closesAt) {
    }

    record ShipmentBatch(int batchNumber, long positionFrom, Long positionTo, LocalDate estimatedShipStart,
                         LocalDate estimatedShipEnd) {
    }
}
