package com.grandis.nova.order.stock.api;

import com.grandis.nova.order.stock.query.StockQueryService.AvailableStock;

import java.util.List;
import java.util.UUID;

/** @param items 요청 순서, 재고 행이 있는 옵션만 */
public record InternalStockResponse(List<Item> items) {

    static InternalStockResponse from(List<AvailableStock> stocks) {
        return new InternalStockResponse(stocks.stream().map(s -> new Item(s.optionId(), s.available())).toList());
    }

    /** @param available 지금 주문할 수 있는 수량(0 이상) — 총량 − 확보 − 판매 */
    public record Item(UUID optionId, int available) {
    }
}
