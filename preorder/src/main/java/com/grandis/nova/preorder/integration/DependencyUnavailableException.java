package com.grandis.nova.preorder.integration;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;

import java.time.Duration;

/** 다른 서비스가 지금 답하지 못하거나 이 서비스가 넘쳤다(503). 다시 시도할 때까지의 권장 대기를 Retry-After 로 알린다. */
public class DependencyUnavailableException extends BusinessException {

    private final Duration retryAfter;
    private final boolean overloaded;

    DependencyUnavailableException(Duration retryAfter) {
        this(retryAfter, false);
    }

    private DependencyUnavailableException(Duration retryAfter, boolean overloaded) {
        super(CommonErrorCode.DEPENDENCY_UNAVAILABLE);
        this.retryAfter = retryAfter;
        this.overloaded = overloaded;
    }

    /** 이 서비스가 넘친 요청을 일부러 돌려보냈다. 거절 수는 보낸 쪽이 지표로 세므로 건마다 경고 로그를 남기지 않는다. */
    public static DependencyUnavailableException overloaded(Duration retryAfter) {
        return new DependencyUnavailableException(retryAfter, true);
    }

    boolean overloaded() {
        return overloaded;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
