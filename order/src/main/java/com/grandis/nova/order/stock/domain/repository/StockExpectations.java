package com.grandis.nova.order.stock.domain.repository;

import com.grandis.nova.order.stock.domain.model.StockBalance;

import java.util.List;
import java.util.UUID;

/**
 * 재고 장부와 주문 기준 수량을 맞대 읽는 포트({@link StockBalance} 의 식). 잠그지 않는다 — 결과마다 SQL 한 문장이라 그 문장의 시점에서 정확하다.
 * 주문 · 드로우 회차 · catalog 옵션 행을 읽기만 한다.
 */
public interface StockExpectations {

    /** 장부와 주문 기준이 다른 옵션 전부, option_id 오름차순. 재고 행이 없는 옵션은 장부 0 으로 본다. 표 전체를 읽는다. */
    List<StockBalance> findDrifted();

    /** 옵션 하나. 주문 · 재고 행이 없어도 0 으로 돌려준다. */
    StockBalance balanceOf(UUID optionId);
}
