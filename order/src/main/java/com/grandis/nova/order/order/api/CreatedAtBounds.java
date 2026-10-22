package com.grandis.nova.order.order.api;

import com.grandis.nova.order.web.ValidationFailures;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** 주문 시각 조건(from · to)을 칼럼에 맞춘다. 관리자 목록과 내 주문 목록이 같이 쓴다. */
final class CreatedAtBounds {

    /** orders.created_at(datetime(6))이 담을 수 있는 범위. */
    private static final Instant CREATED_AT_MIN = Instant.parse("1000-01-01T00:00:00Z");
    private static final Instant CREATED_AT_MAX_EXCLUSIVE = Instant.parse("+10000-01-01T00:00:00Z");

    private CreatedAtBounds() {
    }

    /**
     * 주문 시각 경계를 칼럼(datetime(6))의 정밀도로 올린다 — 저장된 시각은 마이크로초라 올려도 [from, to) 가 그대로다. 그대로 바인딩하면
     * 드라이버가 반올림해 경계의 주문이 한쪽으로 쏠린다. 올린 값이 DATETIME 범위(1000-01-01 ~ 9999-12-31) 밖이면 DB 가 거부해 500 이 되므로 400.
     */
    static Instant of(String field, Instant value) {
        if (value == null) {
            return null;
        }
        Instant micros = value.truncatedTo(ChronoUnit.MICROS);
        if (micros.isBefore(value)) {
            micros = micros.plus(1, ChronoUnit.MICROS);
        }
        if (micros.isBefore(CREATED_AT_MIN) || !micros.isBefore(CREATED_AT_MAX_EXCLUSIVE)) {
            throw ValidationFailures.of(field, "1000-01-01 ~ 9999-12-31 사이 시각이어야 합니다.");
        }
        return micros;
    }
}
