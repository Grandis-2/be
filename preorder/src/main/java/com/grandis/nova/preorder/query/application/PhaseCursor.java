package com.grandis.nova.preorder.query.application;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.common.Cursor;
import com.grandis.nova.preorder.campaign.CampaignSchedule;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;

/** 상품별 운영 현황의 커서 — 마지막 회차의 (마감 시각, 상품 id). 비어 있으면 처음부터다. */
record PhaseCursor(Instant closesAt, UUID productId) {

    static final PhaseCursor FIRST_PAGE = new PhaseCursor(null, null);
    private static final int KEYS = 2;

    static PhaseCursor of(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return FIRST_PAGE;
        }
        String[] keys = Cursor.decode(cursor);
        if (keys.length != KEYS) {
            throw invalid();
        }
        try {
            return new PhaseCursor(Instant.parse(keys[0]), UUID.fromString(keys[1]));
        } catch (DateTimeParseException | IllegalArgumentException e) {
            throw invalid();
        }
    }

    static String after(CampaignSchedule last) {
        return Cursor.encode(last.closesAt(), last.productId());
    }

    private static BusinessException invalid() {
        return new BusinessException(CommonErrorCode.VALIDATION_FAILED, "커서가 올바르지 않습니다.");
    }
}
