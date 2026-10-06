package com.grandis.nova.order.stock.api;

import com.grandis.nova.order.stock.domain.model.StockSetting;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

import java.util.List;

/**
 * 한 상품의 여러 옵션 재고. 관리자 재고 API 본문과 catalog 등록 이벤트(IN_STOCK_PRODUCT_REGISTERED) payload 가 같이 쓴다 —
 * 계약상 같은 모양이라 형식 검증을 한 벌로 둔다. 상한은 catalog 의 상품당 조합 상한(MAX_COMBINATIONS)과 같다.
 * 같은 옵션이 두 번 오는 것은 서비스가 거른다(AdminStockService).
 *
 * 두 칸은 JSON 정수만 받는다. Jackson 기본값(ACCEPT_FLOAT_AS_INT)은 12.5 를 12 로 잘라 다른 옵션 · 다른 수량이
 * 조용히 들어가므로, 전역 설정을 건드리지 않고 이 칸에서만 소수를 거절한다(API 는 400, 이벤트는 재시도 뒤 DLQ).
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
            @JsonDeserialize(using = WholeLong.class) @NotNull @Positive Long optionId,
            @JsonDeserialize(using = WholeInteger.class) @NotNull @PositiveOrZero Integer stockTotal
    ) {
    }

    /** JSON 정수 토큰만 받는다. 범위를 넘으면 파서가 거절한다. */
    static final class WholeLong extends ValueDeserializer<Long> {

        @Override
        public Long deserialize(JsonParser p, DeserializationContext ctxt) {
            return p.currentToken() == JsonToken.VALUE_NUMBER_INT ? p.getLongValue()
                    : (Long) ctxt.handleUnexpectedToken(Long.class, p);
        }
    }

    static final class WholeInteger extends ValueDeserializer<Integer> {

        @Override
        public Integer deserialize(JsonParser p, DeserializationContext ctxt) {
            return p.currentToken() == JsonToken.VALUE_NUMBER_INT ? p.getIntValue()
                    : (Integer) ctxt.handleUnexpectedToken(Integer.class, p);
        }
    }
}
