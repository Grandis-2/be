package com.grandis.nova.payment.vo;

import java.util.Objects;
import java.util.UUID;

/**
 * 거래 행 선점의 표식. 선점할 때마다 새로 발급한다. 결과 반영은 이 값이 행의 것과 같을 때만 된다 —
 * 리스가 만료돼 다른 작업자가 다시 선점했으면 표식이 바뀌어 늦은 쪽의 반영이 0행이 된다.
 */
public record LeaseToken(String value) {

    public LeaseToken {
        Objects.requireNonNull(value, "value");
        if (value.isBlank() || value.length() > 64) {
            throw new IllegalArgumentException("리스 표식은 1~64자다");
        }
    }

    public static LeaseToken issue() {
        return new LeaseToken(UUID.randomUUID().toString());
    }
}
