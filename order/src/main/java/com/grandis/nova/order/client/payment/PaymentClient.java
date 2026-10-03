package com.grandis.nova.order.client.payment;

import com.grandis.nova.common.security.BearerTokens;
import com.grandis.nova.common.web.ApiResponse;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

/**
 * order → payment 서비스 내부 API 호출 클라이언트(선언형 HTTP 인터페이스, 구현은 HttpClientConfig 가 만든다).
 * 결제 거래는 payment 에게 맡긴다 — payment_transactions 를 직접 쓰지 않는다(모듈 경계).
 *
 * 계약: payment InternalPaymentAttemptController. 사용자 액세스 토큰을 그대로 전달하고 payment 는 토큰 유효성만 본다 —
 * 주문의 주인 · 결제할 수 있는 상태인지는 order 가 확인하고 부른다. 토큰 문제 401, 요청 모양 오류 400.
 */
@HttpExchange("/internal/payment-attempts")
public interface PaymentClient {

    /**
     * 결제창을 열 CAPTURE(PENDING)를 새로 만든다. 부를 때마다 새 거래 · 새 결제사 주문 번호다.
     *
     * @param authorization 헤더 값 그대로({@link BearerTokens#value} 로 만든 "Bearer …"). null 이면 싣지 않는다(payment 가 401)
     */
    @PostExchange
    ApiResponse<PaymentAttempt> openCapture(@RequestBody CaptureRequest request,
                                            @RequestHeader(name = BearerTokens.HEADER, required = false)
                                            String authorization);
}
