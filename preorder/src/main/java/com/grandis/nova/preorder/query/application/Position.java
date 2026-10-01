package com.grandis.nova.preorder.query.application;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.common.Cursor;

import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * 커서가 가리키는 자리(마지막으로 본 예약의 접수 시각과 id). 첫 페이지면 비어 있다.
 *
 * 커서는 클라이언트가 그대로 돌려주는 불투명 문자열이라 아무 값이나 올 수 있다.
 * 형식이 틀리면 400 이다 — 해석하다 실패하면 500 이 된다.
 */
record Position(Instant createdAt, Long id) {

    static final Position FIRST_PAGE = new Position(null, null);
    static final int KEYS = 2;

    public static Position of(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return FIRST_PAGE;
        }
        String[] keys = Cursor.decode(cursor);
        if (keys.length != KEYS) {
            throw invalid();
        }
        try {
            return new Position(Instant.parse(keys[0]), Long.valueOf(keys[1]));
        } catch (DateTimeParseException | NumberFormatException e) {
            throw invalid();
        }
    }

    private static BusinessException invalid() {
        return new BusinessException(CommonErrorCode.VALIDATION_FAILED, "커서가 올바르지 않습니다.");
    }
}
