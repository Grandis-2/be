package com.grandis.nova.preorder.integration;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/** 다른 서비스가 지금 답하지 못한다(503). 다시 시도할 때까지의 권장 대기를 Retry-After 로 알린다. */
public class DependencyUnavailableException extends BusinessException {

    private static final Logger log = LoggerFactory.getLogger(DependencyUnavailableException.class);

    private final Duration retryAfter;

    DependencyUnavailableException(Duration retryAfter) {
        super(CommonErrorCode.DEPENDENCY_UNAVAILABLE);
        this.retryAfter = retryAfter;
    }

    /**
     * 호출이 일시 장애(타임아웃 · 연결 실패 · 5xx)로 실패했다. 공통 InternalCallFailures.unavailable 과 같지만 Retry-After 를 싣는다.
     *
     * @param target 로그에 남길 대상(공개 식별자만 — 인증 토큰 · 개인정보를 넣지 않는다)
     */
    public static DependencyUnavailableException callFailed(String dependency, String target, Throwable cause) {
        log.warn("{} 호출 실패 {}", dependency, target, cause);
        return new DependencyUnavailableException(DependencyGuard.SHORT_RETRY_AFTER);
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
