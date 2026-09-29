package com.grandis.nova.catalog.edit;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * PATCH /admin/products/{id}/option-values/{valueId}. null 은 "보내지 않음". 둘 다 null 이면 400.
 *
 * @param value     표시 문구. 정규화값이 같아야 한다(오타 · 띄어쓰기 · 대소문자). 달라지면 400 — 구성 변경은 새 값 추가로
 * @param surcharge 추가금. 이 값을 고른 옵션의 가격을 재계산한다(수동 가격 제외)
 */
public record OptionValueEditRequest(@Size(min = 1, max = 60) String value, @DecimalMin("0") BigDecimal surcharge) {

    public boolean isEmpty() {
        return value == null && surcharge == null;
    }
}
