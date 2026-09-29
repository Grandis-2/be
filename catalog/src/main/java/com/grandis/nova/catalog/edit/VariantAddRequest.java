package com.grandis.nova.catalog.edit;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.Map;

/**
 * POST /admin/products/{id}/variants — 등록 때 제외했거나 새 값이 생겨 아직 없는 조합 하나를 옵션으로 만든다.
 *
 * @param selections 축 키 → 값(축 전부. 키는 소문자로 접고 값은 저장 규칙으로 정규화). 축이 없는 상품은 빈 객체
 * @param sku        비면 등록과 같은 기본값(정규화값을 '-' 로, 축 없으면 STD)
 * @param price      보내면 수동 가격으로 고정. 비면 기본가 + 추가금 합
 */
public record VariantAddRequest(@NotNull Map<String, String> selections, @Size(max = 80) String sku, @DecimalMin("0") BigDecimal price) {
}
