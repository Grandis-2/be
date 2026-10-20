package com.grandis.nova.order.stock.api;

import com.grandis.nova.order.stock.domain.model.StockCorrection;

import java.util.UUID;

/**
 * 보정 결과. 총량은 바꾸지 않는다.
 *
 * @param changed 장부가 바뀌었다(기록을 남겼다). 이미 주문 기준과 같았으면 false
 */
public record StockCorrectionResponse(UUID optionId, boolean registered, int stockTotal, Counts before, Counts after, boolean changed) {

    static StockCorrectionResponse from(StockCorrection correction) {
        return new StockCorrectionResponse(correction.optionId(), correction.registered(), correction.total(),
                new Counts(correction.beforeReserved(), correction.beforeSold()),
                new Counts(correction.afterReserved(), correction.afterSold()), correction.changed());
    }

    public record Counts(int stockReserved, int stockSold) {
    }
}
