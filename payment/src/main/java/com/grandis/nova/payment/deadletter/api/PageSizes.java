package com.grandis.nova.payment.deadletter.api;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.common.web.ApiError;

import java.util.List;
import java.util.Map;

/** 관리자 목록의 크기 · 페이지 규칙. order(web.PageSizes)와 같다 — 모듈 간 공유 금지라 복사한다. 오류 모양도 같다(details.violations). */
final class PageSizes {

    static final int DEFAULT = 20;
    static final int MIN = 1;
    static final int MAX = 100;

    private PageSizes() {
    }

    /** @throws BusinessException VALIDATION_FAILED — 범위를 벗어난 size */
    static int require(int size) {
        if (size < MIN || size > MAX) {
            throw invalid("size", "%d~%d 이어야 합니다.".formatted(MIN, MAX));
        }
        return size;
    }

    /** 페이지 번호(0 부터). 건너뛸 행 수(page × size)가 int 를 넘으면 Spring Data 가 거부해 500 이 되므로 크기와 함께 본다. */
    static int requirePage(int page, int size) {
        if (page < 0) {
            throw invalid("page", "0 이상이어야 합니다.");
        }
        if ((long) page * size > Integer.MAX_VALUE) {
            throw invalid("page", "너무 큽니다.");
        }
        return page;
    }

    private static BusinessException invalid(String field, String message) {
        return new BusinessException(CommonErrorCode.VALIDATION_FAILED,
                Map.of("violations", List.of(new ApiError.Violation(field, message))));
    }
}
