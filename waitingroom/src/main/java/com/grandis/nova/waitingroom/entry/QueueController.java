package com.grandis.nova.waitingroom.entry;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.waitingroom.redis.RedisKeys;
import com.grandis.nova.waitingroom.web.ApiResponse;
import com.grandis.nova.waitingroom.web.RequestIdFilter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.Map;

/** 예약 페이지에 들어올 때 진입하고, 줄에 섰으면 알려 준 간격으로 조회한다. 회원은 액세스 토큰의 sub 다. */
@RestController
@RequestMapping("/api/v1/preorders/queue")
class QueueController {

    static final String QUEUE_TOKEN = "Queue-Token";

    private final AdmissionGate gate;
    private final QueueLookup lookup;
    private final Clock clock;

    QueueController(AdmissionGate gate, QueueLookup lookup, Clock clock) {
        this.gate = gate;
        this.lookup = lookup;
        this.clock = clock;
    }

    /** 통과 · 이미 입장이면 200 입장권, 줄에 섰으면 202 대기 토큰. */
    @PostMapping
    Mono<ResponseEntity<ApiResponse<QueueView>>> enter(@RequestParam String productId,
                                                        @AuthenticationPrincipal Jwt jwt,
                                                        ServerWebExchange exchange) {
        return gate.enter(checked(productId), jwt.getSubject())
                .map(view -> respond(view, view instanceof QueueView.Waiting ? HttpStatus.ACCEPTED : HttpStatus.OK,
                        exchange));
    }

    @GetMapping
    Mono<ResponseEntity<ApiResponse<QueueView>>> status(@RequestParam String productId,
                                                         @RequestHeader(value = QUEUE_TOKEN, required = false) String queueToken,
                                                         @AuthenticationPrincipal Jwt jwt,
                                                         ServerWebExchange exchange) {
        if (queueToken == null || queueToken.isBlank()) {
            return Mono.error(new BusinessException(CommonErrorCode.VALIDATION_FAILED, QUEUE_TOKEN + " 헤더가 필요합니다.",
                    Map.of("field", QUEUE_TOKEN)));
        }
        return lookup.status(checked(productId), jwt.getSubject(), queueToken)
                .map(view -> respond(view, HttpStatus.OK, exchange));
    }

    /** 모델 키가 그대로 Redis 키에 들어가므로 모양부터 본다. */
    private static String checked(String productId) {
        if (!RedisKeys.validProductKey(productId)) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED, "productId 가 올바르지 않습니다.",
                    Map.of("field", "productId"));
        }
        return productId;
    }

    private ResponseEntity<ApiResponse<QueueView>> respond(QueueView view, HttpStatus status, ServerWebExchange exchange) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status);
        if (view instanceof QueueView.Waiting waiting) {
            builder.header(HttpHeaders.RETRY_AFTER, String.valueOf(waiting.retryAfterSeconds()));
        }
        return builder.body(ApiResponse.success(view, clock.instant(), RequestIdFilter.traceId(exchange)));
    }
}
