package com.grandis.nova.waitingroom.redis;

import com.grandis.nova.waitingroom.domain.product.SalesWindow;

import java.time.Instant;
import java.util.Optional;

/**
 * 배분 대상 모델 해시(PRODUCTS)의 값 형식 — "opensAt(ms)|closesAt(ms)|scheduleVersion", 비공개면 번호 앞에 "hidden" 칸.
 * 번호는 늘 마지막 칸이다(일정 반영 Lua 가 그렇게 읽는다). 깨진 값은 그 모델만 빼고 나머지는 계속 돈다.
 */
public final class ProductSchedules {

    private static final String RETIRED_PREFIX = "retired|";
    private static final String HIDDEN = "hidden";

    private ProductSchedules() {
    }

    public static String format(SalesWindow window, long scheduleVersion, boolean visible) {
        return window.opensAt().toEpochMilli() + "|" + window.closesAt().toEpochMilli() + "|"
                + (visible ? "" : HIDDEN + "|") + scheduleVersion;
    }

    /** 끝난 지 오래돼 일정 대신 번호만 남긴 표식인가. 배분 대상이 아니다. */
    public static boolean isRetired(String value) {
        return value != null && value.startsWith(RETIRED_PREFIX);
    }

    /** 은퇴 표식을 남긴 때. 표식이 아니거나 읽을 수 없으면 빈 값. */
    public static Optional<Instant> retiredAt(String value) {
        if (!isRetired(value)) {
            return Optional.empty();
        }
        String[] parts = value.split("\\|", -1);
        try {
            return parts.length == 3 ? Optional.of(Instant.ofEpochMilli(Long.parseLong(parts[2]))) : Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    public static Optional<Schedule> parse(String value) {
        if (value == null || isRetired(value)) {
            return Optional.empty();
        }
        String[] parts = value.split("\\|", -1);
        boolean hidden = parts.length == 4 && HIDDEN.equals(parts[2]);
        if (parts.length != 3 && !hidden) {
            return Optional.empty();
        }
        try {
            SalesWindow window = new SalesWindow(Instant.ofEpochMilli(Long.parseLong(parts[0])),
                    Instant.ofEpochMilli(Long.parseLong(parts[1])));
            return Optional.of(new Schedule(window, Long.parseLong(parts[parts.length - 1]), !hidden));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** @param visible 회원에게 공개한 상품인가. 비공개면 진입을 받지 않고 선 줄은 멈춘다 */
    public record Schedule(SalesWindow window, long scheduleVersion, boolean visible) {

        public String format() {
            return ProductSchedules.format(window, scheduleVersion, visible);
        }
    }
}
