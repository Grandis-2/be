package com.grandis.nova.order.stock.api;

import com.grandis.nova.order.stock.domain.model.StockSetting;
import com.grandis.nova.order.web.WholeInteger;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.annotation.JsonDeserialize;

import java.util.List;
import java.util.UUID;

/**
 * 한 상품의 여러 옵션 재고. 관리자 재고 API 본문과 catalog 등록 이벤트(IN_STOCK_PRODUCT_REGISTERED) payload 가 같이 쓴다 —
 * 계약상 같은 모양이라 형식 검증을 한 벌로 둔다. 상한은 catalog 의 상품당 조합 상한(MAX_COMBINATIONS)과 같다.
 * 같은 옵션이 두 번 오는 것은 서비스가 거른다(AdminStockService).
 *
 * 옵션 id 는 UUID 문자열이다. 수량은 JSON 정수만 받는다({@link WholeInteger} — 소수를 잘라 받지 않는다).
 */
public record StockRequest(
        @NotNull @Size(min = 1, max = MAX_ITEMS) List<@NotNull @Valid Item> items
) {

    public static final int MAX_ITEMS = 500;

    public List<StockSetting> toSettings() {
        return items.stream().map(item -> new StockSetting(item.optionId(), item.stockTotal())).toList();
    }

    /** @param stockTotal 관리자가 이 옵션에 배정하는 총량(창고 실물이 아니다) */
    public record Item(
            @NotNull UUID optionId,
            @JsonDeserialize(using = WholeInteger.class) @NotNull @PositiveOrZero Integer stockTotal
    ) {
    }
}
