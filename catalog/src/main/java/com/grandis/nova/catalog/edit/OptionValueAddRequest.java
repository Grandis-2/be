package com.grandis.nova.catalog.edit;

import com.grandis.nova.catalog.registration.ProductRegistrationRequest;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * POST /admin/products/{id}/option-values — 축에 값을 하나 더한다(새 색상 · 새 용량). 옵션은 만들지 않는다 —
 * 그 값이 든 조합은 POST …/variants 로 하나씩 만든다(어떤 조합을 팔지는 관리자가 고른다).
 *
 * @param hex 색상 스와치(#RRGGBB). color 축의 값만 받는다
 */
public record OptionValueAddRequest(@NotBlank @Size(max = 40) String axisKey, @NotBlank @Size(max = 60) String value,
                                    @DecimalMin("0") BigDecimal surcharge,
                                    @Pattern(regexp = ProductRegistrationRequest.HEX_PATTERN, message = "#RRGGBB 형식이어야 합니다.") String hex) {
}
