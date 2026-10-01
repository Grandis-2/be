package com.grandis.nova.preorder.integration;

import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.common.ErrorCode;
import com.grandis.nova.common.web.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 의존 서비스 · DB 를 지금 쓸 수 없을 때 503 에 Retry-After 를 붙인다. 본문은 공통 처리기와 같다(5xx 는 기본 문구).
 * 저장소(DB · Redis) 연결을 못 얻는 것(풀 고갈 · 연결 실패)은 일시 장애다 — 500 이면 클라이언트가 다시 보내도 되는지 모른다.
 */
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

    /** 과부하 때 건마다 쌓이므로 스택 없이 한 줄만 남긴다. 쿼리 · 제약 오류는 여기 오지 않는다(그대로 500). */
    @ExceptionHandler({CannotCreateTransactionException.class, DataAccessResourceFailureException.class})
    ResponseEntity<ApiResponse<Void>> handleDatabaseUnavailable(Exception e) {
        ErrorCode code = CommonErrorCode.DEPENDENCY_UNAVAILABLE;
        log.warn("저장소를 쓸 수 없음: {}", NestedExceptionUtils.getMostSpecificCause(e).getMessage());
        return ResponseEntity.status(code.status())
                .header(HttpHeaders.RETRY_AFTER, Long.toString(DependencyGuard.SHORT_RETRY_AFTER.toSeconds()))
                .body(ApiResponse.fail(code, code.defaultMessage()));
    }
}
