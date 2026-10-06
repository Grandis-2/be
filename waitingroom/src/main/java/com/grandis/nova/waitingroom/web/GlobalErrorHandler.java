package com.grandis.nova.waitingroom.web;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.common.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

import java.net.ConnectException;
import java.util.concurrent.TimeoutException;

/**
 * 처리하지 못한 예외를 nova 봉투로 바꾼다. Boot 기본 처리기(-1)보다 먼저 선다.
 * 5xx 의 원문은 응답에 싣지 않고 로그에만 남긴다 — 다른 서비스의 처리기와 같은 규칙이다.
 */
@Component
@Order(-2)
class GlobalErrorHandler implements WebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalErrorHandler.class);

    private final ErrorResponses errorResponses;

    GlobalErrorHandler(ErrorResponses errorResponses) {
        this.errorResponses = errorResponses;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(ex);
        }
        // 필터 체인 밖이라 Reactor 컨텍스트의 traceId 가 MDC 에 없다 — 로그를 남기는 동안만 직접 싣는다
        try (MDC.MDCCloseable ignored = MDC.putCloseable(RequestIdFilter.TRACE_ID_KEY, RequestIdFilter.traceId(exchange))) {
            return respond(exchange, ex);
        }
    }

    private Mono<Void> respond(ServerWebExchange exchange, Throwable ex) {
        if (ex instanceof BusinessException business) {
            ErrorCode code = business.errorCode();
            if (code.status() >= 500) {
                log.warn("업무 오류 {}: {}", code.name(), business.getMessage());
                return errorResponses.write(exchange, code, code.defaultMessage(), business.details());
            }
            return errorResponses.write(exchange, code, business.getMessage(), business.details());
        }
        if (ex instanceof ResponseStatusException status) {
            HttpStatusCode statusCode = status.getStatusCode();
            // 전달 대상이 느리거나 못 받는 것(503 · 504)은 우리 결함이 아니고 몰리면 요청마다 쌓인다 — 한 줄로 남긴다
            if (statusCode.value() == 503 || statusCode.value() == 504) {
                log.warn("전달 대상 응답 실패: {}", ex.toString());
            } else if (statusCode.is5xxServerError()) {
                log.error("요청 처리 중 서버 오류", ex);
            }
            exchange.getResponse().getHeaders().putAll(status.getHeaders());
            ErrorCode code = codeOf(statusCode);
            return errorResponses.write(exchange, statusCode, code, code.defaultMessage(), null);
        }
        // 접수 전달 대상(preorder)에 붙지 못했거나 빈 연결을 기다리다 시한이 지났다. 보내지 않았으므로 다시 보내도 된다
        if (hasCause(ex, ConnectException.class) || hasCause(ex, TimeoutException.class)) {
            log.warn("전달 대상에 연결하지 못했다: {}", ex.toString());
            return errorResponses.write(exchange, CommonErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        log.error("처리하지 못한 오류", ex);
        return errorResponses.write(exchange, CommonErrorCode.INTERNAL_ERROR);
    }

    /** 계약에 코드가 있는 상태는 그 코드로, 나머지 4xx 는 VALIDATION_FAILED 로 묶되 상태 코드는 그대로 둔다. */
    private static ErrorCode codeOf(HttpStatusCode status) {
        return switch (status.value()) {
            case 401 -> CommonErrorCode.UNAUTHENTICATED;
            case 403 -> CommonErrorCode.FORBIDDEN;
            case 404 -> CommonErrorCode.NOT_FOUND;
            case 405 -> CommonErrorCode.METHOD_NOT_ALLOWED;
            case 503 -> CommonErrorCode.DEPENDENCY_UNAVAILABLE;
            default -> status.is4xxClientError() ? CommonErrorCode.VALIDATION_FAILED : CommonErrorCode.INTERNAL_ERROR;
        };
    }

    private static boolean hasCause(Throwable ex, Class<? extends Throwable> type) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) {
                return true;
            }
        }
        return false;
    }
}
