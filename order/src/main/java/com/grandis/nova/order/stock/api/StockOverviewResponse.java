package com.grandis.nova.order.stock.api;

import com.grandis.nova.order.stock.admin.StockOverview;

import java.util.List;

/**
 * 관리자 재고 조회. 옵션 전부를 option_id 오름차순으로 싣는다.
 *
 * @param tracked 재고를 세는 상품인가. 사전예약 상품은 false 이고 items 가 비어 있다 — 화면은 재고 탭 · 수량 칸을 숨긴다
 */
public record StockOverviewResponse(Long productId, boolean tracked, List<Item> items) {

    static StockOverviewResponse from(StockOverview overview) {
        return new StockOverviewResponse(overview.productId(), overview.tracked(),
                overview.lines().stream().map(Item::of).toList());
    }

    /**
     * @param registered 재고를 넣었는가. false 면 수량은 모두 0 이고 구매 화면에서 품절로 보인다
     * @param available  총량 − 확보 − 판매
     */
    public record Item(Long optionId, boolean registered, int stockTotal, int stockReserved, int stockSold,
                       int available) {

        static Item of(StockOverview.Line line) {
            if (line.level() == null) {
                return new Item(line.optionId(), false, 0, 0, 0, 0);
            }
            return new Item(line.optionId(), true, line.level().total(), line.level().reserved(), line.level().sold(),
                    line.level().available());
        }
    }
}
