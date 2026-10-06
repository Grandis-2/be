package com.grandis.nova.order.order.query;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.common.Cursor;
import com.grandis.nova.order.order.domain.repository.OrderPosition;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

/**
 * 커서 문자열 ↔ 목록의 자리. 커서는 클라이언트가 그대로 돌려주는 불투명 문자열이라 아무 값이나 올 수 있다.
 * 형식이 틀리거나 저장될 수 없는 값이면 400 이다 — 그대로 쿼리에 넣으면 500 이 된다.
 */
final class OrderCursor {

    static final int KEYS = 2;
    /** MySQL DATETIME 이 담을 수 있는 범위(1000-01-01 ~ 9999-12-31). */
    static final Instant MIN_CREATED_AT = Instant.parse("1000-01-01T00:00:00Z");
    static final Instant MAX_CREATED_AT_EXCLUSIVE = Instant.parse("+10000-01-01T00:00:00Z");

    private OrderCursor() {
    }

    /** @return 첫 페이지면 null */
    static OrderPosition decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        String[] keys = Cursor.decode(cursor);
        if (keys.length != KEYS) {
            throw invalid();
        }
        Instant createdAt;
        long id;
        try {
            // 칼럼(datetime(6))의 정밀도로 먼저 맞춘다. 나노초 그대로 범위를 보면 9999-12-31T23:59:59.9999995Z 가
            // 검사를 통과한 뒤 바인딩에서 반올림돼 10000-01-01 이 된다. 우리가 준 커서는 칼럼 값이라 잃는 것이 없다.
            createdAt = Instant.parse(keys[0]).truncatedTo(ChronoUnit.MICROS);
            id = Long.parseLong(keys[1]);
        } catch (DateTimeParseException | NumberFormatException e) {
            throw invalid();
        }
        // 형식은 맞아도 저장할 수 없는 값이면 DB 가 거부하거나(DATETIME 범위) 변환이 넘친다 — 둘 다 500 이 된다.
        // 우리가 준 커서라면 늘 저장된 행의 값이므로 범위 밖은 조작된 커서다.
        if (createdAt.isBefore(MIN_CREATED_AT) || !createdAt.isBefore(MAX_CREATED_AT_EXCLUSIVE) || id < 1) {
            throw invalid();
        }
        return new OrderPosition(createdAt, id);
    }

    static String encode(OrderPosition position) {
        return Cursor.encode(position.createdAt().toString(), position.id());
    }

    private static BusinessException invalid() {
        return new BusinessException(CommonErrorCode.VALIDATION_FAILED, "커서가 올바르지 않습니다.");
    }
}
