package com.grandis.nova.catalog.product;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DiscountTest {

    @Test
    @DisplayName("할인가는 정가 × (100 − 할인율) / 100 을 100원 단위로 버린다 — 반올림이 아니다")
    void floorsToHundredWon() {
        assertThat(Discount.apply(new BigDecimal("2278100"), 7)).isEqualByComparingTo("2118600");   // 2,118,633
        assertThat(Discount.apply(new BigDecimal("2278190"), 5)).isEqualByComparingTo("2164200");   // 2,164,280.5 — 반올림이면 2,164,300
        assertThat(Discount.apply(new BigDecimal("1000000"), 50)).isEqualByComparingTo("500000");
        assertThat(Discount.apply(new BigDecimal("99"), 1)).isEqualByComparingTo("0");
        assertThat(Discount.apply(new BigDecimal("1000"), 99)).isEqualByComparingTo("0");
        assertThat(Discount.apply(BigDecimal.ZERO, 30)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("할인율 0 은 정가 그대로 — 100원 미만도 버리지 않는다")
    void zeroRateKeepsTheListPrice() {
        assertThat(Discount.apply(new BigDecimal("2278190"), 0)).isEqualByComparingTo("2278190");
    }

    @Test
    @DisplayName("할인율은 0~99 — 밖이면 거절한다")
    void rateMustBeZeroToNinetyNine() {
        assertThatThrownBy(() -> Discount.apply(BigDecimal.TEN, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Discount.apply(BigDecimal.TEN, 100)).isInstanceOf(IllegalArgumentException.class);
        assertThat(Discount.requireRate(0)).isZero();
        assertThat(Discount.requireRate(99)).isEqualTo(99);
    }
}
