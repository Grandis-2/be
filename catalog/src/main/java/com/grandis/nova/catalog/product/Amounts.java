package com.grandis.nova.catalog.product;

import java.math.BigDecimal;

/**
 * 원 단위 금액 검사. 칼럼이 decimal(12,0) 이라 소수는 DB 가 조용히 반올림한다 —
 * 같은 트랜잭션의 엔티티는 1000.5, DB 는 1001 이 되어 갈린다(실측). 그래서 소수를 여기서 거절한다.
 */
public final class Amounts {

    private Amounts() {
    }

    /** 0 이상의 정수 원 금액만 통과한다. */
    public static BigDecimal requireWholeWon(BigDecimal amount, String name) {
        if (amount == null || amount.signum() < 0) {
            throw new IllegalArgumentException(name + " must be zero or positive: " + amount);
        }
        if (amount.stripTrailingZeros().scale() > 0) {
            throw new IllegalArgumentException(name + " must be a whole number of won: " + amount);
        }
        return amount;
    }
}
