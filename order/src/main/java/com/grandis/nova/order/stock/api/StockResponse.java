package com.grandis.nova.order.stock.api;

import com.grandis.nova.order.stock.admin.StockResult;
import com.grandis.nova.order.stock.domain.model.StockLevel;

import java.util.List;
import java.util.UUID;

/** 처리 뒤 옵션별 재고. 가용 = 총량 − 확보 − 판매. */
public record StockResponse(UUID productId, List<Item> items) {

    static StockResponse from(StockResult result) {
        return new StockResponse(result.productId(), result.levels().stream()
                .map(level -> Item.of(level, result.created().contains(level.optionId())))
                .toList());
    }

    /** @param created 이번 요청이 행을 새로 만들었다 */
    public record Item(UUID optionId, int stockTotal, int stockReserved, int stockSold, int available,
                       boolean created) {

        static Item of(StockLevel level, boolean created) {
            return new Item(level.optionId(), level.total(), level.reserved(), level.sold(), level.available(),
                    created);
        }
    }
}
