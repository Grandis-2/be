package com.grandis.nova.catalog.product;

import com.grandis.nova.catalog.option.OptionCombination;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AmountsTest {

    @ParameterizedTest
    @ValueSource(strings = {"0", "1000", "1000.0", "1000.00", "1.2E+3"})
    @DisplayName("정수 원 금액은 표기가 달라도 통과한다")
    void wholeWonPasses(String amount) {
        BigDecimal given = new BigDecimal(amount);
        assertThat(Amounts.requireWholeWon(given, "x")).isSameAs(given);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1000.5", "0.01", "-1", "-0.5"})
    @DisplayName("소수와 음수는 거절한다 — decimal(12,0) 이 조용히 반올림하기 전에")
    void fractionsAndNegativesRejected(String amount) {
        assertThatThrownBy(() -> Amounts.requireWholeWon(new BigDecimal(amount), "x"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("x");
    }

    @Test
    @DisplayName("null 은 거절한다")
    void nullRejected() {
        assertThatThrownBy(() -> Amounts.requireWholeWon(null, "x")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("상품 · 옵션 팩토리가 같은 규칙을 쓴다")
    void factoriesUseTheRule() {
        assertThatThrownBy(() -> Product.register(1L, SaleMode.IN_STOCK, "x", new BigDecimal("1000.5"), null, null,
                false, BigDecimal.ZERO)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("basePrice");
        assertThatThrownBy(() -> Product.register(1L, SaleMode.IN_STOCK, "x", BigDecimal.ZERO, null, null,
                true, new BigDecimal("0.5"))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("warrantySurcharge");
        assertThatThrownBy(() -> ProductOption.of("sku", new BigDecimal("10.5"), false, OptionCombination.none(1L, "x")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("price");
    }
}
