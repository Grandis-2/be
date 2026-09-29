package com.grandis.nova.catalog.edit;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * PATCH /admin/products/{id} 본문. null 은 "보내지 않음" 이고 그 칸은 그대로다. 전부 null 이면 400.
 * 길이 · 금액 규칙은 등록과 같다. saleMode · 판매 상태 · 공개 여부는 여기서 못 바꾼다(별도 명령).
 */
public record ProductEditRequest(
        @Size(min = 1, max = 100) String title,
        @Size(max = 5000) String description,
        @Size(max = 500) String tags,
        @DecimalMin("0") BigDecimal basePrice,
        @Valid Warranty warranty
) {

    public record Warranty(@NotNull Boolean offered, @DecimalMin("0") BigDecimal surcharge) {
    }

    public boolean isEmpty() {
        return title == null && description == null && tags == null && basePrice == null && warranty == null;
    }
}
