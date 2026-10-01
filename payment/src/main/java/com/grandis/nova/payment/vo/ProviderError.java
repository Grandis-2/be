package com.grandis.nova.payment.vo;

import java.util.Objects;

/**
 * 결제사가 알린 오류(또는 호출 쪽이 붙인 코드: 타임아웃 등). last_error_code · last_error_message 에 남는다.
 *
 * 칸보다 길면 자른다 — 거부하면 결과 반영 자체가 실패해 결제 결과를 잃는다. 문자 중간(대리 쌍)에서 자르지 않는다.
 * DB 칸 길이는 문자(코드 포인트) 수다(utf8mb4).
 *
 * @param message 없을 수 있다
 */
public record ProviderError(String code, String message) {

    /** last_error_code varchar(100). */
    public static final int CODE_MAX = 100;
    /** last_error_message varchar(500). */
    public static final int MESSAGE_MAX = 500;

    public ProviderError {
        Objects.requireNonNull(code, "code");
        if (code.isBlank()) {
            throw new IllegalArgumentException("오류 코드가 비었다");
        }
        code = truncate(code, CODE_MAX);
        message = message == null ? null : truncate(message, MESSAGE_MAX);
    }

    private static String truncate(String value, int maxCodePoints) {
        if (value.codePointCount(0, value.length()) <= maxCodePoints) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, maxCodePoints));
    }
}
