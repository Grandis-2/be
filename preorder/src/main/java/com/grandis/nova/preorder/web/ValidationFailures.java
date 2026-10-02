package com.grandis.nova.preorder.web;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.common.web.ApiError;

import java.util.List;
import java.util.Map;

/** 서비스 · 컨트롤러가 직접 판정한 입력 오류. 스프링 검증과 같은 모양(details.violations)으로 낸다. */
public final class ValidationFailures {

    private ValidationFailures() {
    }

    public static BusinessException of(String field, String message) {
        return new BusinessException(CommonErrorCode.VALIDATION_FAILED,
                Map.of("violations", List.of(new ApiError.Violation(field, message))));
    }
}
