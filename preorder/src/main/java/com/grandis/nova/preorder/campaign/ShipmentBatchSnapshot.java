package com.grandis.nova.preorder.campaign;

import java.time.LocalDate;

/** 모듈 밖에 내주는 배송 차수. positionTo 가 null 이면 상한 없는 마지막 차수다. */
public record ShipmentBatchSnapshot(
        Long id,
        int batchNumber,
        long positionFrom,
        Long positionTo,
        LocalDate estimatedShipStart,
        LocalDate estimatedShipEnd
) {

    static ShipmentBatchSnapshot of(ShipmentBatch batch) {
        return new ShipmentBatchSnapshot(batch.getId(), batch.getBatchNumber(), batch.getPositionFrom(),
                batch.getPositionTo(), batch.getEstimatedShipStart(), batch.getEstimatedShipEnd());
    }
}
