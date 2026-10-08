package com.grandis.nova.order.order.vo;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 주문의 공개 식별자(UUID). 밖에는 이것만 알린다 — 내부 id(UUID v7)는 만든 시각이 드러난다.
 * DB 칸이 대소문자를 구별하므로(utf8mb4_bin) 소문자 표준형만 받는다.
 */
public record OrderToken(String value) {

    public OrderToken {
        Objects.requireNonNull(value, "value");
        if (!isCanonicalUuid(value)) {
            throw new IllegalArgumentException("주문 토큰은 소문자 UUID 여야 한다: " + value);
        }
    }

    public static OrderToken issue() {
        return new OrderToken(UUID.randomUUID().toString());
    }

    /** 경로로 받은 토큰. 형식이 틀리면 없는 주문과 같다 — 400 으로 나눠 봐야 호출자가 얻는 정보가 없다(404). */
    public static Optional<OrderToken> parse(String value) {
        return value != null && isCanonicalUuid(value) ? Optional.of(new OrderToken(value)) : Optional.empty();
    }

    private static boolean isCanonicalUuid(String value) {
        try {
            return UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
