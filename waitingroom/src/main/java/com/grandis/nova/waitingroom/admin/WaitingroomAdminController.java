package com.grandis.nova.waitingroom.admin;

import com.grandis.nova.waitingroom.control.OperationalSettings;
import com.grandis.nova.waitingroom.web.ApiResponse;
import com.grandis.nova.waitingroom.web.RequestIdFilter;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

import java.time.Clock;

/**
 * 운영값과 현황. 관리자(role = ADMIN)만 — 보안 설정이 경로로 막는다. 바꾼 값은 다음 회차(1초 안)에 적용된다.
 * 변경 뒤 다시 읽다 503 이 나도 값은 이미 바뀌었을 수 있다 — 같은 요청을 다시 보내도 결과가 같다.
 */
@RestController
@RequestMapping("/api/v1/admin/waitingroom")
class WaitingroomAdminController {

    private final OperationalSettingsAdmin settings;
    private final Clock clock;

    WaitingroomAdminController(OperationalSettingsAdmin settings, Clock clock) {
        this.settings = settings;
        this.clock = clock;
    }

    @GetMapping("/admission-rate")
    Mono<ApiResponse<AdmissionRateView>> admissionRate(ServerWebExchange exchange) {
        return settings.admissionRate().map(view -> ok(view, exchange));
    }

    /** 0 은 입장 일시 정지(줄은 그대로). */
    @PutMapping("/admission-rate")
    Mono<ApiResponse<AdmissionRateView>> setGlobalCredit(@RequestBody GlobalCreditRequest request,
                                                          @AuthenticationPrincipal Jwt jwt, ServerWebExchange exchange) {
        return settings.setGlobalCredit(jwt.getSubject(),
                OperationalSettingsAdmin.integral(request.globalCredit(), "globalCredit")).map(view -> ok(view, exchange));
    }

    @DeleteMapping("/admission-rate")
    Mono<ApiResponse<AdmissionRateView>> clearGlobalCredit(@AuthenticationPrincipal Jwt jwt, ServerWebExchange exchange) {
        return settings.clear(jwt.getSubject(), OperationalSettings.GLOBAL_CREDIT).map(view -> ok(view, exchange));
    }

    @PutMapping("/products/{productId}/admission-rate")
    Mono<ApiResponse<AdmissionRateView>> setProductCap(@PathVariable String productId, @RequestBody ProductCapRequest request,
                                                        @AuthenticationPrincipal Jwt jwt, ServerWebExchange exchange) {
        return settings.setProductCap(jwt.getSubject(), productId,
                OperationalSettingsAdmin.integral(request.cap(), "cap")).map(view -> ok(view, exchange));
    }

    @DeleteMapping("/products/{productId}/admission-rate")
    Mono<ApiResponse<AdmissionRateView>> clearProductCap(@PathVariable String productId, @AuthenticationPrincipal Jwt jwt,
                                                          ServerWebExchange exchange) {
        return settings.clearProductCap(jwt.getSubject(), productId).map(view -> ok(view, exchange));
    }

    /** 줄 최대 길이 = 입장 속도 × 이 값. 지우면 제한 없음. */
    @PutMapping("/max-wait")
    Mono<ApiResponse<AdmissionRateView>> setMaxWait(@RequestBody MaxWaitRequest request, @AuthenticationPrincipal Jwt jwt,
                                                     ServerWebExchange exchange) {
        return settings.setMaxWait(jwt.getSubject(),
                OperationalSettingsAdmin.integral(request.seconds(), "seconds")).map(view -> ok(view, exchange));
    }

    @DeleteMapping("/max-wait")
    Mono<ApiResponse<AdmissionRateView>> clearMaxWait(@AuthenticationPrincipal Jwt jwt, ServerWebExchange exchange) {
        return settings.clear(jwt.getSubject(), OperationalSettings.MAX_WAIT_SEC).map(view -> ok(view, exchange));
    }

    @GetMapping("/status")
    Mono<ApiResponse<WaitingroomStatusView>> status(ServerWebExchange exchange) {
        return settings.status().map(view -> ok(view, exchange));
    }

    private <T> ApiResponse<T> ok(T data, ServerWebExchange exchange) {
        return ApiResponse.success(data, clock.instant(), RequestIdFilter.traceId(exchange));
    }

    /** 값은 JSON 그대로 받아 정수인지 직접 본다. */
    record GlobalCreditRequest(JsonNode globalCredit) {
    }

    record ProductCapRequest(JsonNode cap) {
    }

    record MaxWaitRequest(JsonNode seconds) {
    }
}
