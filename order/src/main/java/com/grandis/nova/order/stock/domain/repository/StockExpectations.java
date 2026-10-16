package com.grandis.nova.order.stock.domain.repository;

import com.grandis.nova.order.stock.domain.model.StockBalance;

import java.util.List;
import java.util.UUID;

/**
 * 재고 장부와 주문 기준 수량을 맞대 읽는 포트({@link StockBalance} 의 식). 잠그지 않는다 — 결과마다 SQL 한 문장이라 그 문장의 시점에서 정확하다.
 * 주문 · 드로우 회차 · catalog 옵션 행을 읽기만 한다.
 */
public interface StockExpectations {

    /**
     * 장부와 주문 기준이 다른 옵션의 수와, option_id 오름차순으로 앞 limit 개. 재고 행이 없는 옵션은 장부 0 으로 본다. 표 전체를 읽지만 앱으로
     * 가져오는 것은 앞 limit 개뿐이다 — 어긋남이 아무리 많아도 메모리가 늘지 않는다. 수와 목록은 같은 문장(같은 시점)이다.
     */
    Drifts findDrifted(int limit);

    /** 옵션 하나. 주문 · 재고 행이 없어도 0 으로 돌려준다. */
    StockBalance balanceOf(UUID optionId);

    /** @param total 어긋난 옵션 수(전부) · @param first 그 가운데 앞 limit 개 */
    record Drifts(int total, List<StockBalance> first) {

        public Drifts {
            first = List.copyOf(first);
        }
    }
}
