package com.grandis.nova.order.stock.domain.model;

/** 옵션 하나에 줄 총량. 관리자 입력은 HTTP 경계에서 먼저 400 으로 거른다 — 여기서 나는 예외는 호출 코드의 잘못이다. */
public record StockSetting(Long optionId, int total) {

    public StockSetting {
        if (optionId == null) {
            throw new IllegalArgumentException("옵션 id 가 없다");
        }
        if (total < 0) {
            throw new IllegalArgumentException("총량은 0 이상이다: " + total);
        }
    }
}
