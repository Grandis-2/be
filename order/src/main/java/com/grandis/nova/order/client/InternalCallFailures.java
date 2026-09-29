package com.grandis.nova.order.client;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.UnknownContentTypeException;

/**
 * 다른 서비스 내부 API 호출이 실패했을 때의 공통 분류. 기준은 "다시 부르면 나아지는가" 하나다.
 *
 * - 연동 오류(500) — 다시 불러도 같다. 4xx(각 호출이 따로 다루는 것 제외)와, 응답은 왔지만 읽을 수 없는 경우(본문 모양 ·
 *   Content-Type 이 계약과 다름). 사용자 잘못이 아니므로 상대의 상태를 그대로 돌려주지 않는다(문구는 공통 처리기가 숨긴다).
 * - 일시 장애(503) — 타임아웃 · 연결 실패 · 5xx. 다시 시도를 안내한다. 연동 오류를 여기로 보내면 그 안내가 거짓이 된다.
 *
 * 4xx · 5xx 규칙은 preorder 의 같은 이름 클래스와 같다. 읽을 수 없는 응답을 500 으로 가르는 것은 order 에만 있다.
 */
public final class InternalCallFailures {

    private static final Logger log = LoggerFactory.getLogger(InternalCallFailures.class);

    private InternalCallFailures() {
    }

    /** @param target 로그에 남길 대상(공개 식별자만 — 인증 토큰 · 개인정보를 넣지 않는다) */
    public static IllegalStateException integrationError(String dependency, String target,
                                                         HttpClientErrorException cause) {
        log.error("{} 연동 오류 {} status={}", dependency, target, cause.getStatusCode(), cause);
        return new IllegalStateException(dependency + " 연동 오류: " + cause.getStatusCode(), cause);
    }

    /**
     * 응답을 받았지만 읽을 수 없다 — 계약 위반. RestClient 는 변환 실패를 RestClientException 으로 감싸 올리고(원인
     * HttpMessageNotReadableException), 읽을 변환기가 없는 Content-Type 은 UnknownContentTypeException 자체로 올린다.
     * 타임아웃 · 연결 실패(ResourceAccessException) · 5xx 는 여기 들지 않는다.
     */
    public static boolean isUnreadableResponse(RestClientException e) {
        return e instanceof UnknownContentTypeException || e.getCause() instanceof HttpMessageNotReadableException;
    }

    /** @param target 로그에 남길 대상(공개 식별자만 — 인증 토큰 · 개인정보를 넣지 않는다) */
    public static IllegalStateException unreadableResponse(String dependency, String target, RestClientException cause) {
        log.error("{} 연동 오류 {} 응답을 읽을 수 없음", dependency, target, cause);
        return new IllegalStateException(dependency + " 연동 오류: 응답을 읽을 수 없음", cause);
    }

    public static BusinessException unavailable(String dependency, String target, Throwable cause) {
        log.warn("{} 호출 실패 {}", dependency, target, cause);
        return new BusinessException(CommonErrorCode.DEPENDENCY_UNAVAILABLE);
    }
}
