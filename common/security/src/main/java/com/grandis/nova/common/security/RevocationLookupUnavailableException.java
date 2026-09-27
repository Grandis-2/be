package com.grandis.nova.common.security;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;

/**
 * 폐기 여부를 확인해야 하는데 조회가 안 됐고, 이 경로(재발급)는 조회 실패 시 닫기로 한 경로다. 401 UNAUTHENTICATED,
 * `error.details.retryable = true`(JsonAuthFailureHandlers.RETRYABLE_DETAILS 와 같은 값 — 필터가 닫는 경로에서 내는 401 과 같은 의미).
 * 필터를 거치지 않는 공개 경로(쿠키로 식별하는 재발급)에서 서비스가 던지고, 공통 GlobalExceptionHandler 가 details 를 그대로 봉투에 싣는다.
 */
public class RevocationLookupUnavailableException extends BusinessException {

    public RevocationLookupUnavailableException() {
        super(CommonErrorCode.UNAUTHENTICATED, JsonAuthFailureHandlers.RETRYABLE_DETAILS);
    }
}
