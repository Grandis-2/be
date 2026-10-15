package com.grandis.nova.member.notification;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.common.Cursor;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * 커서 문자열 ↔ 목록의 자리(마지막으로 본 알림의 occurred_at · id). 정렬이 둘의 내림차순이라 함께 든다 — 시각 하나면 같은 시각의 알림이
 * 페이지 경계에서 새거나 겹친다. 클라이언트가 돌려주는 불투명 문자열이라 형식이 틀리거나 DATETIME 범위 밖이면 400 이다(order 의 OrderCursor 와 같은 규칙).
 */
record NotificationCursor(Instant occurredAt, UUID id) {

    private static final Instant MIN = Instant.parse("1000-01-01T00:00:00Z");
    private static final Instant MAX_EXCLUSIVE = Instant.parse("+10000-01-01T00:00:00Z");

    /** @return 첫 페이지면 null */
    static NotificationCursor decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        String[] keys = Cursor.decode(cursor);
        if (keys.length != 2) {
            throw invalid();
        }
        Instant occurredAt;
        UUID id;
        try {
            // 칼럼 정밀도(마이크로초)로 먼저 자른다 — 나노초째 범위를 보면 바인딩의 반올림이 범위를 넘길 수 있다
            occurredAt = Instant.parse(keys[0]).truncatedTo(ChronoUnit.MICROS);
            id = UUID.fromString(keys[1]);
        } catch (DateTimeParseException | IllegalArgumentException e) {
            throw invalid();
        }
        if (occurredAt.isBefore(MIN) || !occurredAt.isBefore(MAX_EXCLUSIVE)) {
            throw invalid();
        }
        return new NotificationCursor(occurredAt, id);
    }

    static String encode(Notification last) {
        return Cursor.encode(last.getOccurredAt().toString(), last.getId());
    }

    private static BusinessException invalid() {
        return new BusinessException(CommonErrorCode.VALIDATION_FAILED, "커서가 올바르지 않습니다.");
    }
}
