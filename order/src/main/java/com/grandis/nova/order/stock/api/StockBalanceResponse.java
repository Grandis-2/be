package com.grandis.nova.order.stock.api;

import com.grandis.nova.order.stock.domain.model.StockBalance;

import java.util.UUID;

/**
 * 한 옵션의 장부와 주문 기준 수량(보정 미리보기). 같은 시점에 읽은 값이다.
 *
 * @param registered 재고 행이 있는가. 없으면 장부 쪽(stockTotal · stockReserved · stockSold)은 0
 * @param matches    장부가 주문 기준과 같다 — 다르면 보정(POST)이 장부를 주문 기준으로 덮는다
 */
public record StockBalanceResponse(UUID optionId, boolean registered, int stockTotal, int stockReserved, int stockSold,
                                   long expectedReserved, long expectedSold, boolean matches) {

    static StockBalanceResponse from(StockBalance balance) {
        return new StockBalanceResponse(balance.optionId(), balance.registered(), balance.total(), balance.actualReserved(),
                balance.actualSold(), balance.expectedReserved(), balance.expectedSold(), balance.matches());
    }
}
