package com.grandis.nova.catalog.integration;

import com.grandis.nova.common.web.ApiResponse;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * order 내부 API — 옵션별 남은 수량(contracts/order-internal.md 의 GET /internal/stocks). 토큰 없이 부른다 — 계약이 토큰을 받지 않고, 실으면 호출마다
 * order 의 폐기 조회 · 만료 토큰 WARN 만 늘어난다. 그래서 토큰 릴레이가 붙지 않는 그룹({@link Dependencies#ORDER_STOCKS})에 둔다.
 */
@HttpExchange("/internal/stocks")
public interface StockClient {

    /** @param optionIds 1~100개. 재고 행이 없는 옵션은 결과에서 빠진다 */
    @GetExchange
    ApiResponse<Stocks> getStocks(@RequestParam("optionIds") Collection<UUID> optionIds);

    record Stocks(List<Stock> items) {
    }

    /** @param available 지금 주문할 수 있는 수량(0 이상) */
    record Stock(UUID optionId, Integer available) {
    }
}
