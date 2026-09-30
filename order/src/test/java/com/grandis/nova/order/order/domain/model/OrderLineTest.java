package com.grandis.nova.order.order.domain.model;

import com.grandis.nova.order.order.vo.Money;
import com.grandis.nova.order.order.vo.Quantity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderLineTest {

    // product_title_snapshot 100 · option_title_snapshot 120. 글자 수로 센다.
    @Test
    void titleLengthsMatchDatabaseColumns() {
        assertThat(line("가".repeat(100), "나".repeat(120)).subtotal()).isEqualTo(Money.won(1000));
        assertThatThrownBy(() -> line("가".repeat(101), "옵션")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> line("상품", "나".repeat(121))).isInstanceOf(IllegalArgumentException.class);
    }

    private static OrderLine line(String productTitle, String optionTitle) {
        return new OrderLine(1L, 2L, Quantity.ONE, Money.won(1000), productTitle, optionTitle);
    }
}
