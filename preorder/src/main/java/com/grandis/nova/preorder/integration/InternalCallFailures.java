package com.grandis.nova.preorder.integration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.HttpClientErrorException;

/**
 * 다른 서비스 내부 API 호출이 실패했을 때의 공통 분류. catalog · order 클라이언트가 같이 쓴다.
 *
 * - 4xx(각 호출이 따로 다루는 것 제외): 다시 불러도 같은 결과인 연동 오류(계약 불일치 등)다.
 *   사용자 잘못이 아니므로 상대의 상태를 그대로 돌려주지 않고 500 으로 둔다(문구는 공통 처리기가 숨긴다).
 * - 타임아웃 · 연결 실패 · 5xx: 일시 장애라 503 으로 다시 시도를 안내한다.
 */
public final class InternalCallFailures {

    private static final Logger log = LoggerFactory.getLogger(InternalCallFailures.class);

    private InternalCallFailures() {
    }

    /** @param target 로그에 남길 대상(공개 식별자만 — 토큰 · 개인정보를 넣지 않는다) */
    public static IllegalStateException integrationError(String dependency, String target,
                                                         HttpClientErrorException cause) {
        log.error("{} 연동 오류 {} status={}", dependency, target, cause.getStatusCode(), cause);
        return new IllegalStateException(dependency + " 연동 오류: " + cause.getStatusCode(), cause);
    }

    public static DependencyUnavailableException unavailable(String dependency, String target, Throwable cause) {
        log.warn("{} 호출 실패 {}", dependency, target, cause);
        return new DependencyUnavailableException(DependencyGuard.SHORT_RETRY_AFTER);
    }
}
