package com.grandis.nova.order.order.domain.model;

import com.grandis.nova.order.order.vo.Money;
import com.grandis.nova.order.order.vo.Quantity;
import com.grandis.nova.order.support.TestIds;
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

    @Test
    void subtotalAddsWarrantyPriceTimesWarrantyQuantity() {
        OrderLine line = new OrderLine(TestIds.id(1), TestIds.id(2), new Quantity(3), Money.won(1_250_000), 2, Money.won(199_000),
                "상품", "옵션");

        assertThat(line.subtotal()).isEqualTo(Money.won(3 * 1_250_000 + 2 * 199_000));
        assertThat(line("상품", "옵션").subtotal()).isEqualTo(Money.won(1000));
    }

    // ck_order_item_warranty_quantity(0 ~ 수량) · 보증을 사지 않았으면 보증가 0
    @Test
    void warrantyQuantityStaysWithinQuantityAndUnboughtWarrantyIsFree() {
        assertThatThrownBy(() -> new OrderLine(TestIds.id(1), TestIds.id(2), new Quantity(2), Money.won(1000), 3, Money.won(10), "상품", "옵션"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderLine(TestIds.id(1), TestIds.id(2), new Quantity(2), Money.won(1000), -1, Money.won(10), "상품", "옵션"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderLine(TestIds.id(1), TestIds.id(2), new Quantity(2), Money.won(1000), 0, Money.won(10), "상품", "옵션"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new OrderLine(TestIds.id(1), TestIds.id(2), new Quantity(2), Money.won(1000), 2, Money.won(10), "상품", "옵션")
                .warrantyQuantity()).isEqualTo(2);
    }

    private static OrderLine line(String productTitle, String optionTitle) {
        return OrderLine.withoutWarranty(TestIds.id(1), TestIds.id(2), Quantity.ONE, Money.won(1000), productTitle, optionTitle);
    }
}
