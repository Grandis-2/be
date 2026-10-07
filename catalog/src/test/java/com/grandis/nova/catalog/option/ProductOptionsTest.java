package com.grandis.nova.catalog.option;

import com.grandis.nova.catalog.option.ProductOptions.Warranty;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProductOptionsTest {

    @Test
    @DisplayName("보증 추가금은 소수점 없는 정수 원으로 담긴다 — 1.5e3 이 JSON 에 1.5E+3 으로 들어가지 않는다. 제공하지 않으면 0")
    void warrantySurchargeIsPlainInteger() {
        Warranty offered = new Warranty(true, new BigDecimal("1.5e3"));
        assertThat(offered.surcharge().toPlainString()).isEqualTo("1500");
        assertThat(new ProductOptions(List.of(), List.of(), List.of(), offered).toJson()).contains("\"surcharge\":1500");
        assertThat(new Warranty(false, new BigDecimal("99")).surcharge()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("보증 키가 없는 문서(옮기기 전 · 다른 모듈 픽스처의 {})는 보증 없음으로 읽는다")
    void missingWarrantyReadsAsNone() {
        assertThat(ProductOptions.parse("{}").warranty()).isEqualTo(Warranty.NONE);
        assertThat(ProductOptions.parse("{\"axes\":[]}").warranty()).isEqualTo(Warranty.NONE);
        assertThat(ProductOptions.parse("{\"warranty\":{\"offered\":true,\"surcharge\":199000}}").warranty())
                .isEqualTo(new Warranty(true, new BigDecimal("199000")));
    }
}
