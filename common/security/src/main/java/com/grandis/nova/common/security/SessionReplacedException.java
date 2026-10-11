package com.grandis.nova.common.security;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;

/**
 * 같은 회원이 다른 곳에서 새로 로그인해 이 세션이 끊겼다. 401 UNAUTHENTICATED, `error.details.reason = SESSION_REPLACED`
 * (JsonAuthFailureHandlers.SESSION_REPLACED_DETAILS 와 같은 값 — 필터가 끊긴 액세스 토큰에 내는 401 과 같은 의미).
 * 필터를 거치지 않는 공개 경로(쿠키로 식별하는 재발급)에서 서비스가 던지고, 공통 GlobalExceptionHandler 가 details 를 그대로 봉투에 싣는다.
 */
public class SessionReplacedException extends BusinessException {

    public SessionReplacedException() {
        super(CommonErrorCode.UNAUTHENTICATED, JsonAuthFailureHandlers.SESSION_REPLACED_DETAILS);
    }
}
