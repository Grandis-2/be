package com.grandis.nova.order.client.payment;

import com.grandis.nova.common.security.BearerTokens;
import com.grandis.nova.common.web.ApiResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

/**
 * order → payment 승인 호출(선언형 HTTP 인터페이스). 준비({@link PaymentClient})와 그룹을 나눈다 — payment 가 토스를 최대 60초
 * 기다리므로 읽기 기한이 길다(spring.http.serviceclient.payment-confirm, 70s).
 *
 * 계약: payment InternalPaymentAttemptController#confirm. 사용자 액세스 토큰을 그대로 전달한다.
 */
@HttpExchange("/internal/payment-attempts")
public interface PaymentConfirmClient {

    /** @param authorization 헤더 값 그대로({@link BearerTokens#value} 로 만든 "Bearer …"). null 이면 싣지 않는다(payment 가 401) */
    @PostExchange("/{providerOrderId}/confirm")
    ApiResponse<ConfirmReply> confirm(@PathVariable String providerOrderId, @RequestBody ConfirmRequest request,
                                      @RequestHeader(name = BearerTokens.HEADER, required = false) String authorization);
}
