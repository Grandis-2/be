package com.grandis.nova.preorder.query;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.preorder.PreorderErrorCode;
import com.grandis.nova.preorder.web.ValidationFailures;

import java.util.List;
import java.util.Map;

/** 내부 메모 수정 본문. 이 칸 하나만 받는다(계약: 다른 필드가 있으면 409). */
final class InternalNotes {

    static final String FIELD = "internalNote";
    static final int MAX_LENGTH = 1000;

    private InternalNotes() {
    }

    /**
     * @throws BusinessException PREORDER_FIELD_IMMUTABLE — 메모 밖의 필드를 보냈다
     * @throws BusinessException VALIDATION_FAILED — 메모 칸이 없거나 문자열이 아니거나 너무 길다
     */
    static String require(Map<String, Object> request) {
        List<String> others = request.keySet().stream().filter(key -> !FIELD.equals(key)).toList();
        if (!others.isEmpty()) {
            throw new BusinessException(PreorderErrorCode.PREORDER_FIELD_IMMUTABLE, Map.of("fields", others));
        }
        if (!request.containsKey(FIELD)) {
            throw ValidationFailures.of(FIELD, "필수 항목입니다.");
        }
        Object value = request.get(FIELD);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String note)) {
            throw ValidationFailures.of(FIELD, "문자열이어야 합니다.");
        }
        if (note.length() > MAX_LENGTH) {
            throw ValidationFailures.of(FIELD, "%d자 이하여야 합니다.".formatted(MAX_LENGTH));
        }
        return note;
    }
}
