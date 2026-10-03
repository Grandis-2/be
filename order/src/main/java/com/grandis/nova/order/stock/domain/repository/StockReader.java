package com.grandis.nova.order.stock.domain.repository;

import com.grandis.nova.order.stock.domain.model.StockLevel;

import java.util.Collection;
import java.util.List;

/** 재고 읽기 포트. 잠그지 않는다. 같은 트랜잭션에서 쓴 값은 보인다. */
public interface StockReader {

    /** 행이 있는 옵션만, option_id 오름차순. */
    List<StockLevel> findByOptionIds(Collection<Long> optionIds);
}
