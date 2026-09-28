package com.grandis.nova.preorder.campaign;


import java.time.LocalDate;

/** openapi ShipmentBatch. positionTo 가 null 이면 상한 없는 마지막 차수다. */
public record ShipmentBatchResponse(
        int batchNumber,
        long positionFrom,
        Long positionTo,
        LocalDate estimatedShipStart,
        LocalDate estimatedShipEnd
) {

    public static ShipmentBatchResponse from(ShipmentBatch batch) {
        return new ShipmentBatchResponse(batch.getBatchNumber(), batch.getPositionFrom(), batch.getPositionTo(),
                batch.getEstimatedShipStart(), batch.getEstimatedShipEnd());
    }
}
