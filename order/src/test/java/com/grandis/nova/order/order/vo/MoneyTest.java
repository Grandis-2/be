package com.grandis.nova.order.order.vo;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @Test
    void sameAmountWithDifferentScaleIsEqual() {
        assertThat(new Money(new BigDecimal("1000.00"))).isEqualTo(Money.won(1000));
    }

    @Test
    void rejectsNegativeFractionalAndTooLongAmounts() {
        assertThatThrownBy(() -> Money.won(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Money(new BigDecimal("0.5"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.won(1_000_000_000_000L)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsTwelveDigits() {
        assertThat(Money.won(999_999_999_999L).amount()).isEqualByComparingTo("999999999999");
    }

    @Test
    void timesAndPlus() {
        assertThat(Money.won(1000).times(new Quantity(3)).plus(Money.won(250))).isEqualTo(Money.won(3250));
    }

    // 합계가 DB 칸을 넘으면 저장 전에 막힌다.
    @Test
    void sumBeyondTwelveDigitsIsRejected() {
        assertThatThrownBy(() -> Money.won(999_999_999_999L).plus(Money.won(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
