package com.grandis.nova.preorder.campaign;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.LocalDate;
import java.util.List;

/** 배송 차수 전체 교체 요청. 구간이 이어지는지 · 상한 없는 차수가 하나인지는 ShipmentBatchPlan 이 본다. */
record ShipmentBatchesRequest(
        @NotEmpty @Valid List<@NotNull Line> batches
) {

    public ShipmentBatchPlan toPlan() {
        return new ShipmentBatchPlan(batches.stream()
                .map(line -> new ShipmentBatchPlan.Line(line.batchNumber(), line.positionFrom(), line.positionTo(),
                        line.estimatedShipStart(), line.estimatedShipEnd()))
                .toList());
    }

    /** positionTo 가 null 이면 상한 없는 마지막 차수다. */
    public record Line(
            @NotNull @Positive Integer batchNumber,
            @NotNull @Positive Long positionFrom,
            @PositiveOrZero Long positionTo,
            @NotNull LocalDate estimatedShipStart,
            @NotNull LocalDate estimatedShipEnd
    ) {
    }
}
