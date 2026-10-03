package com.grandis.nova.preorder.integration;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;

import java.time.Duration;

/** 다른 서비스가 지금 답하지 못한다(503). 다시 시도할 때까지의 권장 대기를 Retry-After 로 알린다. */
public class DependencyUnavailableException extends BusinessException {

    private final Duration retryAfter;

    DependencyUnavailableException(Duration retryAfter) {
        super(CommonErrorCode.DEPENDENCY_UNAVAILABLE);
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
