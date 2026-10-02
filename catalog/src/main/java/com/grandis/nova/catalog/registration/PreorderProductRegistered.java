package com.grandis.nova.catalog.registration;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.grandis.nova.catalog.outbox.AggregateType;
import com.grandis.nova.catalog.outbox.OutboundEventType;
import com.grandis.nova.catalog.outbox.OutboxMessage;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 사전예약 상품을 등록했다 — preorder 가 회차와 배송 차수를 만든다(preorder-events). 계약: contracts/catalog-events.md.
 *
 * 받는 쪽이 다시 읽을 원장이 catalog 에 없다(회차 · 차수는 preorder 표의 값이다) — 그래서 값을 payload 에 싣는다. preorder 의 다른 이벤트처럼
 * "식별자만 싣고 원장을 다시 읽는" 모양이 아니다. 상품 id 는 봉투의 aggregateId 로만 간다.
 *
 * @param campaign        회차 시각. 마이크로초로 잘라 싣는다 — preorder 는 datetime(6) 에 저장한다
 * @param shipmentBatches 배송 차수 전체. 요청 순서 그대로
 */
public record PreorderProductRegistered(
        @JsonIgnore Long productId,
        Campaign campaign,
        List<ShipmentBatch> shipmentBatches
) implements OutboxMessage {

    public PreorderProductRegistered {
        shipmentBatches = List.copyOf(shipmentBatches);
    }

    static PreorderProductRegistered of(Long productId, ProductRegistrationRequest.Campaign campaign,
                                        List<ProductRegistrationRequest.ShipmentBatch> batches) {
        return new PreorderProductRegistered(productId,
                new Campaign(campaign.opensAt().truncatedTo(ChronoUnit.MICROS), campaign.closesAt().truncatedTo(ChronoUnit.MICROS)),
                batches.stream().map(batch -> new ShipmentBatch(batch.batchNumber(), batch.positionFrom(), batch.positionTo(),
                        batch.estimatedShipStart(), batch.estimatedShipEnd())).toList());
    }

    @Override
    public OutboundEventType eventType() {
        return OutboundEventType.PREORDER_PRODUCT_REGISTERED;
    }

    @Override
    public AggregateType aggregateType() {
        return AggregateType.PRODUCT;
    }

    @Override
    public Long aggregateId() {
        return productId;
    }

    public record Campaign(Instant opensAt, Instant closesAt) {
    }

    /** @param positionTo null 이면 상한 없는 마지막 차수 */
    public record ShipmentBatch(int batchNumber, long positionFrom, Long positionTo, LocalDate estimatedShipStart,
                                LocalDate estimatedShipEnd) {
    }
}
