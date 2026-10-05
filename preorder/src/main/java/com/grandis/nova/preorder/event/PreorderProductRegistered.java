package com.grandis.nova.preorder.event;

import com.grandis.nova.preorder.campaign.CampaignRegistration;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * catalog 가 사전예약 상품을 등록했다. 회차 · 차수 값의 원장이 catalog 에 없어 payload 에 실려 온다.
 * 상품 id 는 봉투의 aggregateId 다.
 */
record PreorderProductRegistered(Campaign campaign, List<ShipmentBatch> shipmentBatches) {

    CampaignRegistration toRegistration() {
        if (campaign == null || shipmentBatches == null) {
            throw new IllegalArgumentException("회차 또는 배송 차수가 없는 상품 등록 이벤트");
        }
        return new CampaignRegistration(campaign.opensAt(), campaign.closesAt(), shipmentBatches.stream()
                .map(batch -> new CampaignRegistration.Batch(batch.batchNumber(), batch.positionFrom(),
                        batch.positionTo(), batch.estimatedShipStart(), batch.estimatedShipEnd()))
                .toList());
    }

    record Campaign(Instant opensAt, Instant closesAt) {
    }

    record ShipmentBatch(int batchNumber, long positionFrom, Long positionTo, LocalDate estimatedShipStart,
                         LocalDate estimatedShipEnd) {
    }
}
