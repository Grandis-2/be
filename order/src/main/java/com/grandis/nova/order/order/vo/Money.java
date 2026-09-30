package com.grandis.nova.order.order.vo;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * 원 단위 금액. 음수 · 소수 · 12자리 초과를 받지 않는다(DB decimal(12,0) · ck_order_amount · ck_order_item_price 와 같은 규칙).
 * 비교가 스케일에 흔들리지 않도록 스케일 0 으로 맞춰 둔다 — 1000 과 1000.0 은 같은 금액이다.
 */
public record Money(BigDecimal amount) {

    public static final Money ZERO = new Money(BigDecimal.ZERO);

    static final int MAX_DIGITS = 12;

    public Money {
        Objects.requireNonNull(amount, "amount");
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("금액은 0 이상이어야 한다: " + amount);
        }
        try {
            amount = amount.setScale(0, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("금액은 원 단위여야 한다: " + amount, e);
        }
        if (amount.precision() > MAX_DIGITS) {
            throw new IllegalArgumentException("금액이 %d자리를 넘는다: %s".formatted(MAX_DIGITS, amount));
        }
    }

    public static Money won(long amount) {
        return new Money(BigDecimal.valueOf(amount));
    }

    public Money plus(Money other) {
        return new Money(amount.add(other.amount));
    }

    public Money times(Quantity quantity) {
        return new Money(amount.multiply(BigDecimal.valueOf(quantity.value())));
    }
}
