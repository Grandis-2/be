package com.grandis.nova.payment.client.toss;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

/**
 * 토스페이먼츠 코어 API(결제) 선언형 클라이언트. 구현은 HttpClientConfig 의 "toss" 그룹이 만들고, 주소 · 타임아웃은
 * spring.http.serviceclient.toss, 인증(Basic base64(secretKey + ":"))은 {@link TossAuthorization} 이 싣는다.
 *
 * 직접 부르지 않는다 — 응답 분류(D8)를 거치도록 {@link TossPaymentClient} 로만 쓴다(ArchUnit 이 막는다). 실패는 RestClient 예외로
 * 올라온다. ResponseEntity 로 받는 것은 따라가지 않은 3xx 와 빈 2xx 를 가르기 위해서다.
 */
@HttpExchange(url = "/v1/payments", accept = "application/json")
public interface TossPaymentsApi {

    @PostExchange(url = "/confirm", contentType = "application/json")
    ResponseEntity<TossPayment> confirm(@RequestHeader(TossIdempotencyKey.HEADER) String idempotencyKey,
                        @RequestBody TossConfirmRequest request);

    @PostExchange(url = "/{paymentKey}/cancel", contentType = "application/json")
    ResponseEntity<TossPayment> cancel(@PathVariable String paymentKey,
                       @RequestHeader(TossIdempotencyKey.HEADER) String idempotencyKey,
                       @RequestBody TossCancelRequest.Body body);

    @GetExchange("/{paymentKey}")
    ResponseEntity<TossPayment> getByPaymentKey(@PathVariable String paymentKey);

    /** 승인된 결제만 찾는다(토스 문서) — 404 가 "실패" 를 뜻하지 않는다. */
    @GetExchange("/orders/{orderId}")
    ResponseEntity<TossPayment> getByOrderId(@PathVariable String orderId);
}
