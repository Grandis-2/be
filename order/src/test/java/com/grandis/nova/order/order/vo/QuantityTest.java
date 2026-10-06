package com.grandis.nova.order.order.vo;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuantityTest {

    @Test
    void mustBePositive() {
        assertThat(new Quantity(1)).isEqualTo(Quantity.ONE);
        assertThatThrownBy(() -> new Quantity(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Quantity(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
