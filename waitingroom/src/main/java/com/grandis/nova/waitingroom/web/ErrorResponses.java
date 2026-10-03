package com.grandis.nova.waitingroom.web;

import com.grandis.nova.common.ErrorCode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.Map;
import java.util.Optional;

/** 필터 · 예외 처리기처럼 컨트롤러 밖에서 실패 봉투를 직접 쓴다. */
@Component
public class ErrorResponses {

    private final JsonMapper jsonMapper;
    private final Clock clock;

    ErrorResponses(JsonMapper jsonMapper, Clock clock) {
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    public Mono<Void> write(ServerWebExchange exchange, ErrorCode code) {
        return write(exchange, code, code.defaultMessage(), null);
    }

    public Mono<Void> write(ServerWebExchange exchange, ErrorCode code, String message, Map<String, Object> details) {
        return write(exchange, HttpStatusCode.valueOf(code.status()), code, message, details);
    }

    /** 상태 코드를 코드의 기본값과 다르게 둘 때(415 를 VALIDATION_FAILED 로 묶는 경우 등). */
    public Mono<Void> write(ServerWebExchange exchange, HttpStatusCode status, ErrorCode code, String message,
                            Map<String, Object> details) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        retryAfter(status).ifPresent(seconds -> {
            if (!response.getHeaders().containsHeader(HttpHeaders.RETRY_AFTER)) {
                response.getHeaders().set(HttpHeaders.RETRY_AFTER, seconds);
            }
        });
        ApiResponse<Void> body = ApiResponse.failure(code, message, details, clock.instant(),
                RequestIdFilter.traceId(exchange));
        // 직렬화 실패도 Mono 의 오류로 흘려 호출자의 오류 처리를 탄다
        return response.writeWith(Mono.fromCallable(
                () -> response.bufferFactory().wrap(jsonMapper.writeValueAsBytes(body))));
    }

    /** 다시 올 때를 알려 준다 — 줄이 찼으면 조금 길게, 일시 장애면 짧게. 모두가 바로 다시 오면 같은 순간에 또 몰린다. */
    private static Optional<String> retryAfter(HttpStatusCode status) {
        return switch (status.value()) {
            case 429 -> Optional.of("10");
            case 503 -> Optional.of("2");
            default -> Optional.empty();
        };
    }
}
