package com.grandis.nova.order.stock.domain.repository;

import com.grandis.nova.order.stock.domain.model.StockAdjustment;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/** 관리자 보정 기록(stock_adjustments). */
public interface StockAdjustments {

    void record(StockAdjustment adjustment);

    /** 옵션마다 since 뒤 마지막 보정 시각. 보정이 없는 옵션은 싣지 않는다. */
    Map<UUID, Instant> lastCorrectedSince(Collection<UUID> optionIds, Instant since);
}
