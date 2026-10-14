package com.grandis.nova.catalog.product;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 상품 할인율(정수 %)로 할인가를 계산한다. 할인가 = 정가 × (100 − 할인율) / 100 을 100원 단위로 버린 값이고, 할인율이 0 이면 정가 그대로다.
 * 목록 SQL 의 최저가 · 가격순도 같은 식을 쓴다({@link #sql}) — 둘이 갈리면 목록과 상세의 가격이 달라진다.
 */
public final class Discount {

    public static final int MIN_RATE = 0;
    public static final int MAX_RATE = 99;

    /** 할인가를 이 단위(원) 아래에서 버린다. */
    private static final BigDecimal UNIT = BigDecimal.valueOf(100);
    /** 할인율의 % (100) × 버림 단위(100). */
    private static final BigDecimal PERCENT_TIMES_UNIT = BigDecimal.valueOf(10_000);

    private Discount() {
    }

    /** 정가에 할인율을 건 값. 정가는 0 이상의 정수 원이다(옵션 가격 칼럼). */
    public static BigDecimal apply(BigDecimal listPrice, int rate) {
        requireRate(rate);
        if (rate == 0) {
            return listPrice;
        }
        return listPrice.multiply(BigDecimal.valueOf(100 - rate))
                .divide(PERCENT_TIMES_UNIT, 0, RoundingMode.FLOOR)
                .multiply(UNIT);
    }

    /** {@link #apply} 와 같은 식의 SQL. listPrice · rate 는 SQL 식이다. listPrice 가 NULL 이면 NULL. */
    public static String sql(String listPrice, String rate) {
        return "(CASE WHEN %2$s = 0 THEN %1$s ELSE FLOOR(%1$s * (100 - %2$s) / 10000) * 100 END)".formatted(listPrice, rate);
    }

    public static int requireRate(int rate) {
        if (rate < MIN_RATE || rate > MAX_RATE) {
            throw new IllegalArgumentException("discountRate must be between " + MIN_RATE + " and " + MAX_RATE + ": " + rate);
        }
        return rate;
    }
}
