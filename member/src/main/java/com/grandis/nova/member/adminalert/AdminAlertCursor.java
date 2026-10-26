package com.grandis.nova.member.adminalert;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.common.Cursor;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * 커서 문자열 ↔ 목록의 자리(마지막으로 본 알림의 raised_at · id). 규칙은 회원 알림의 NotificationCursor 와 같다 — 형식이 틀리거나
 * DATETIME 범위 밖이면 400 이다. 다시 올린 알림은 맨 앞으로 옮겨 가므로, 쪽을 넘기는 사이에 올라간 알림은 다음 쪽에 나오지 않는다(첫 쪽을 다시 읽으면 보인다).
 */
record AdminAlertCursor(Instant raisedAt, UUID id) {

    private static final Instant MIN = Instant.parse("1000-01-01T00:00:00Z");
    private static final Instant MAX_EXCLUSIVE = Instant.parse("+10000-01-01T00:00:00Z");

    /** @return 첫 쪽이면 null */
    static AdminAlertCursor decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        String[] keys = Cursor.decode(cursor);
        if (keys.length != 2) {
            throw invalid();
        }
        Instant raisedAt;
        UUID id;
        try {
            raisedAt = Instant.parse(keys[0]).truncatedTo(ChronoUnit.MICROS);
            id = UUID.fromString(keys[1]);
        } catch (DateTimeParseException | IllegalArgumentException e) {
            throw invalid();
        }
        if (raisedAt.isBefore(MIN) || !raisedAt.isBefore(MAX_EXCLUSIVE)) {
            throw invalid();
        }
        return new AdminAlertCursor(raisedAt, id);
    }

    static String encode(AdminAlertQueries.View last) {
        return Cursor.encode(last.raisedAt().toString(), last.id());
    }

    private static BusinessException invalid() {
        return new BusinessException(CommonErrorCode.VALIDATION_FAILED, "커서가 올바르지 않습니다.");
    }
}
