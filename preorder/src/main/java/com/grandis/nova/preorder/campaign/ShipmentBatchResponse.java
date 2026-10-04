package com.grandis.nova.preorder.campaign;

import com.grandis.nova.preorder.campaign.domain.ShipmentBatch;

import java.time.LocalDate;

/** openapi ShipmentBatch. positionTo 가 null 이면 상한 없는 마지막 차수다. */
public record ShipmentBatchResponse(
        int batchNumber,
        long positionFrom,
        Long positionTo,
        LocalDate estimatedShipStart,
        LocalDate estimatedShipEnd
) {

    public static ShipmentBatchResponse from(ShipmentBatchSnapshot batch) {
        return new ShipmentBatchResponse(batch.batchNumber(), batch.positionFrom(), batch.positionTo(),
                batch.estimatedShipStart(), batch.estimatedShipEnd());
    }

    public static ShipmentBatchResponse from(ShipmentBatch batch) {
        return from(batch.toSnapshot());
    }
}
