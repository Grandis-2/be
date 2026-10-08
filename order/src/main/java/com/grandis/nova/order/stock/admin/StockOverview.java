package com.grandis.nova.order.stock.admin;

import com.grandis.nova.order.stock.domain.model.StockLevel;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 관리자 화면의 상품 재고.
 *
 * @param tracked 재고를 세는 상품인가(일반 판매). 사전예약이면 false 이고 lines 는 비어 있다
 * @param lines   그 상품 옵션 전부, option_id 오름차순
 */
public record StockOverview(UUID productId, boolean tracked, List<Line> lines) {

    static StockOverview untracked(UUID productId) {
        return new StockOverview(productId, false, List.of());
    }

    static StockOverview of(UUID productId, List<UUID> optionIds, List<StockLevel> levels) {
        Map<UUID, StockLevel> byOption = levels.stream()
                .collect(Collectors.toMap(StockLevel::optionId, Function.identity()));
        return new StockOverview(productId, true,
                optionIds.stream().sorted().map(id -> new Line(id, byOption.get(id))).toList());
    }

    /** @param level 재고 행. 아직 넣지 않았으면 null */
    public record Line(UUID optionId, StockLevel level) {
    }
}
