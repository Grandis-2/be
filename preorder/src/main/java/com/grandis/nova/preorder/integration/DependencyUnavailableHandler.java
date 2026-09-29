package com.grandis.nova.preorder.integration;

import com.grandis.nova.common.ErrorCode;
import com.grandis.nova.common.web.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 의존 서비스 장애의 503 에 Retry-After 를 붙인다. 본문은 공통 처리기와 같다(5xx 는 기본 문구). */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class DependencyUnavailableHandler {

    private static final Logger log = LoggerFactory.getLogger(DependencyUnavailableHandler.class);

    @ExceptionHandler(DependencyUnavailableException.class)
    ResponseEntity<ApiResponse<Void>> handle(DependencyUnavailableException e) {
        ErrorCode code = e.errorCode();
        log.warn("업무 오류 {}: {}", code.name(), e.getMessage());
        return ResponseEntity.status(code.status())
                .header(HttpHeaders.RETRY_AFTER, Long.toString(Math.max(1, e.retryAfter().toSeconds())))
                .body(ApiResponse.fail(code, code.defaultMessage(), e.details()));
    }
}
