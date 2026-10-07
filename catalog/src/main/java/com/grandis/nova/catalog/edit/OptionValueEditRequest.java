package com.grandis.nova.catalog.edit;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * PATCH /admin/products/{id}/option-values/{valueId}. null 은 "보내지 않음". 둘 다 null 이면 400.
 *
 * @param value     값 이름(오타 · 표시 문구). 정규화값도 같이 바뀌고 옵션 표시명 · 필터 · 표시 속성, 색상이면 사진 묶음이 따라간다. 같은 축의 같은 값은 400
 * @param surcharge 추가금. 이 값을 고른 옵션의 가격을 재계산한다
 */
public record OptionValueEditRequest(@Size(min = 1, max = 60) String value, @DecimalMin("0") BigDecimal surcharge) {

    @Schema(hidden = true)   // 문서 전용 — 없으면 swagger 가 getter 꼴 메서드를 empty 칸으로 그린다
    public boolean isEmpty() {
        return value == null && surcharge == null;
    }
}
