package com.grandis.nova.catalog.option;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.text.Normalizer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductOptionAxisTest {

    @Test
    @DisplayName("축 키는 트림해 소문자로 접는다 — Color 도 필터 축이다")
    void axisKeyIsFolded() {
        ProductOptionAxis axis = ProductOptionAxis.of(1L, " Color ", "색상", 0);
        assertThat(axis.getAxisKey()).isEqualTo("color");
        assertThat(axis.isFilterAxis()).isTrue();
        assertThat(ProductOptionAxis.of(1L, "STORAGE", "용량", 1).isFilterAxis()).isTrue();
        assertThat(ProductOptionAxis.of(1L, "Length", "길이", 2).isFilterAxis()).isFalse();
    }

    @Test
    @DisplayName("값은 NFC · 트림 · 공백 하나로 접어 저장한다 — 콜레이션이 안 해 주는 부분")
    void valuesAreNormalized() {
        ProductOptionValue value = ProductOptionValue.of(1L, "  Space   Gray ", "space  gray", BigDecimal.ZERO, 0);
        assertThat(value.getValue()).isEqualTo("Space Gray");
        assertThat(value.getNormalizedValue()).isEqualTo("space gray");
        // NFD(자모 분해) 로 들어온 한글도 NFC 한 형태로 — 리터럴은 둘 다 NFC 라 명시적으로 분해해 넣는다
        String decomposed = Normalizer.normalize("블랙", Normalizer.Form.NFD);
        assertThat(decomposed).isNotEqualTo("블랙");
        assertThat(ProductOptionValue.normalize(decomposed)).isEqualTo("블랙");
        assertThatThrownBy(() -> ProductOptionValue.normalize("   ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProductOptionValue.normalize(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("빈 키와 음수 위치는 거절한다")
    void invalidInputsRejected() {
        assertThatThrownBy(() -> ProductOptionAxis.of(1L, " ", "x", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProductOptionAxis.of(1L, "color", "x", -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProductOptionValue.of(1L, "x", "x", new BigDecimal("0.5"), 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("surcharge");
    }
}
