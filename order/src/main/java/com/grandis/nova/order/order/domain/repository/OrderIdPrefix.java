package com.grandis.nova.order.order.domain.repository;

import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 주문 번호 앞부분. 화면의 주문번호는 orders.id 의 앞 8자리라 그 앞부분을 id 범위 [lowest, highest] 로 바꿔 기본 키로 찾는다
 * (id 는 바이트를 뒤섞지 않고 저장한다 — UuidBinary). 32자리면 lowest == highest 로 정확히 일치다.
 *
 * id 는 UUID v7 이라 앞 8자리는 만든 시각의 윗부분이다 — 약 65초 동안 만든 주문은 앞 8자리가 같다.
 */
public record OrderIdPrefix(UUID lowest, UUID highest) {

    public static final int MIN_LENGTH = 8;
    private static final int FULL_LENGTH = 32;
    private static final Pattern HEX = Pattern.compile("[0-9a-f]{" + MIN_LENGTH + "," + FULL_LENGTH + "}");

    /** 하이픈을 빼고 16진수 8~32자리면 그 범위, 아니면 빈 값. 대소문자는 가리지 않는다. */
    public static Optional<OrderIdPrefix> parse(String value) {
        String hex = value.replace("-", "").toLowerCase(Locale.ROOT);
        if (!HEX.matcher(hex).matches()) {
            return Optional.empty();
        }
        return Optional.of(new OrderIdPrefix(uuid(pad(hex, '0')), uuid(pad(hex, 'f'))));
    }

    public boolean exact() {
        return lowest.equals(highest);
    }

    /** 앞부분 뒤를 글자(4비트) 단위로 채운다 — 바이트 단위로 채우면 홀수 길이에서 범위가 어긋난다. */
    private static String pad(String hex, char filler) {
        return hex + String.valueOf(filler).repeat(FULL_LENGTH - hex.length());
    }

    private static UUID uuid(String hex) {
        return new UUID(HexFormat.fromHexDigitsToLong(hex, 0, 16), HexFormat.fromHexDigitsToLong(hex, 16, 32));
    }
}
