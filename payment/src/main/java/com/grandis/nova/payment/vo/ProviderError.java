package com.grandis.nova.payment.vo;

import java.util.Objects;

/**
 * 결제 거래의 마지막 오류. 결제사가 알린 오류(또는 호출 쪽이 붙인 코드: 타임아웃 등)와, 승인 · 취소 명령의 결과 불명이면 그 사유다.
 * last_error_code · last_error_message · last_unknown_reason 에 남는다.
 *
 * 칸보다 길면 자른다 — 거부하면 결과 반영 자체가 실패해 결제 결과를 잃는다. 문자 중간(대리 쌍)에서 자르지 않는다.
 * DB 칸 길이는 문자(코드 포인트) 수다(utf8mb4).
 *
 * @param message       없을 수 있다. 결과 불명이면 사유 이름이다 — 다음 복구가 같은 키로 다시 보낼지 · 조회할지를 이것으로 정한다
 * @param unknownReason 명령의 결과 불명이면 그 사유 이름(문구와 같다), 아니면 null. 같이 도는 구버전이 이 칸을 몰라 판정은 아직 문구로 한다
 */
public record ProviderError(String code, String message, String unknownReason) {

    /** last_error_code varchar(100). */
    public static final int CODE_MAX = 100;
    /** last_error_message varchar(500). */
    public static final int MESSAGE_MAX = 500;
    /** last_unknown_reason varchar(30). */
    public static final int UNKNOWN_REASON_MAX = 30;

    public ProviderError {
        Objects.requireNonNull(code, "code");
        if (code.isBlank()) {
            throw new IllegalArgumentException("오류 코드가 비었다");
        }
        code = truncate(code, CODE_MAX);
        message = message == null ? null : truncate(message, MESSAGE_MAX);
        unknownReason = unknownReason == null ? null : truncate(unknownReason, UNKNOWN_REASON_MAX);
    }

    /** 결과 불명이 아닌 오류. */
    public ProviderError(String code, String message) {
        this(code, message, null);
    }

    /** 승인 · 취소 명령의 결과 불명. 사유 이름을 문구 칸과 사유 칸에 같이 적는다. */
    public static ProviderError unknown(String code, String reason) {
        return new ProviderError(code, reason, reason);
    }

    private static String truncate(String value, int maxCodePoints) {
        if (value.codePointCount(0, value.length()) <= maxCodePoints) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, maxCodePoints));
    }
}
