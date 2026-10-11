package com.grandis.nova.preorder.campaign;

import java.time.LocalDate;
import java.util.UUID;

/** 모듈 밖에 내주는 배송 차수. positionTo 가 null 이면 상한 없는 마지막 차수다. */
public record ShipmentBatchSnapshot(
        UUID id,
        int batchNumber,
        long positionFrom,
        Long positionTo,
        LocalDate estimatedShipStart,
        LocalDate estimatedShipEnd
) {
}
