package com.grandis.nova.order.stock.domain.model;

import java.util.UUID;

/** 옵션 하나에 줄 총량. 입력은 받는 쪽(관리자 API · 등록 이벤트)이 먼저 거른다 — 여기서 나는 예외는 호출 코드의 잘못이다. */
public record StockSetting(UUID optionId, int total) {

    public StockSetting {
        if (optionId == null) {
            throw new IllegalArgumentException("옵션 id 가 없다");
        }
        if (total < 0) {
            throw new IllegalArgumentException("총량은 0 이상이다: " + total);
        }
    }
}
