package com.grandis.nova.catalog.product;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 원 단위 금액 검사. 금액 칼럼이 decimal(12,0) 이라 소수는 DB 가 조용히 반올림한다 —
 * 같은 트랜잭션의 엔티티는 1000.5, DB 는 1001 이 되어 갈린다(실측). 그래서 소수를 여기서 거절한다.
 * 상한도 그 칼럼이 정한다 — 열두 자리를 넘으면 INSERT · UPDATE 가 실패한다(실측: 999,999,999,999 는 들어가고 1,000,000,000,000 은 500 이었다).
 * 옵션 문서(JSON) 안의 금액(값 추가금 · 보증 추가금)은 칼럼이 없지만 옵션 가격 계산 · 주문 금액으로 같은 칼럼에 들어가므로 같은 상한을 쓴다.
 */
public final class Amounts {

    /** decimal(12,0) 이 담는 가장 큰 금액. */
    public static final BigDecimal MAX_WON = new BigDecimal("999999999999");

    private Amounts() {
    }

    /**
     * 0 이상 {@link #MAX_WON} 이하의 정수 원 금액만 통과하고, 소수점 없는 표기(scale 0)로 돌려준다 — 1e3 · 1000.0 도 1000 으로 저장 · 응답된다.
     * 안 맞추면 같은 트랜잭션의 응답이 받은 표기(1E+3 · 1000.0)를 그대로 낸다(실측).
     */
    public static BigDecimal requireWholeWon(BigDecimal amount, String name) {
        if (amount == null || amount.signum() < 0) {
            throw new IllegalArgumentException(name + " must be zero or positive: " + amount);
        }
        if (amount.stripTrailingZeros().scale() > 0) {
            throw new IllegalArgumentException(name + " must be a whole number of won: " + amount);
        }
        if (amount.compareTo(MAX_WON) > 0) {
            throw new IllegalArgumentException(name + " exceeds " + MAX_WON + ": " + amount);
        }
        return amount.setScale(0, RoundingMode.UNNECESSARY);
    }
}
