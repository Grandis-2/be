package com.grandis.nova.order.stock.domain.model;

import java.util.UUID;

/**
 * 관리자 보정 한 번의 결과 — 재고 행을 잠근 채 주문 기준 수량으로 확보 · 판매를 덮은 것. 총량은 바꾸지 않는다.
 *
 * @param registered 재고 행이 있는가. 없으면(주문 기준도 0 일 때만 여기 온다) 아무것도 바꾸지 않았다
 */
public record StockCorrection(UUID optionId, boolean registered, int total, int beforeReserved, int beforeSold,
                              int afterReserved, int afterSold) {

    public boolean changed() {
        return beforeReserved != afterReserved || beforeSold != afterSold;
    }
}
