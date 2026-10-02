package com.grandis.nova.order.stock.admin;

import com.grandis.nova.order.stock.domain.model.StockLevel;

import java.util.List;
import java.util.Set;

/**
 * 처리 뒤 재고.
 *
 * @param levels  요청한 옵션의 행, option_id 오름차순
 * @param created 이번 요청이 새로 만든 행의 옵션 id
 */
public record StockResult(Long productId, List<StockLevel> levels, Set<Long> created) {
}
