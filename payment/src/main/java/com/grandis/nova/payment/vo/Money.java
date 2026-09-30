package com.grandis.nova.payment.vo;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * 원 단위 결제 금액. 음수 · 소수 · 12자리 초과를 받지 않는다(DB decimal(12,0) · ck_payment_amount · ck_payment_tx_numbers).
 * 비교가 스케일에 흔들리지 않도록 스케일 0 으로 맞춰 둔다 — 1000 과 1000.0 은 같은 금액이다.
 * 주문의 금액 타입과 따로 둔다 — 결제는 다른 서비스라 주문 코드에 기대지 않고, 결제에는 합산 · 곱셈이 필요 없다.
 */
public record Money(BigDecimal amount) {

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
}
