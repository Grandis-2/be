package com.grandis.nova.waitingroom.redis;

import com.grandis.nova.waitingroom.domain.product.SalesWindow;

import java.time.Instant;
import java.util.Optional;

/**
 * 배분 대상 모델 해시(PRODUCTS)의 값 형식 — "opensAt(ms)|closesAt(ms)|scheduleVersion".
 * 회차 일정 이벤트를 받는 쪽이 쓰고 리더가 읽는다. 깨진 값은 그 모델만 빼고 나머지는 계속 돈다.
 */
public final class ProductSchedules {

    private ProductSchedules() {
    }

    public static String format(SalesWindow window, long scheduleVersion) {
        return window.opensAt().toEpochMilli() + "|" + window.closesAt().toEpochMilli() + "|" + scheduleVersion;
    }

    public static Optional<Schedule> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String[] parts = value.split("\\|", -1);
        if (parts.length != 3) {
            return Optional.empty();
        }
        try {
            SalesWindow window = new SalesWindow(Instant.ofEpochMilli(Long.parseLong(parts[0])),
                    Instant.ofEpochMilli(Long.parseLong(parts[1])));
            return Optional.of(new Schedule(window, Long.parseLong(parts[2])));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public record Schedule(SalesWindow window, long scheduleVersion) {
    }
}
