package com.grandis.nova.payment.api;

import com.grandis.nova.common.web.ApiResponse;
import com.grandis.nova.payment.prepare.PreparePaymentService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 서비스 간 내부 API. ALB 를 거치지 않는다. 호출자(order · draw)는 받은 사용자 토큰을 그대로 싣는다(Authorization: Bearer) —
 * 인증은 보안 설정이 경로로 요구하고(/internal/**), 대상의 주인 · 상태 확인은 호출자가 이미 했다.
 */
@RestController
@RequestMapping("/internal/payment-attempts")
public class InternalPaymentAttemptController {

    private final PreparePaymentService prepareService;

    public InternalPaymentAttemptController(PreparePaymentService prepareService) {
        this.prepareService = prepareService;
    }

    /** 결제창을 열 CAPTURE(PENDING)를 새로 만든다. 부를 때마다 새 거래다. 금액은 승인 때 다시 대조한다({@link OpenCaptureRequest}). */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<PaymentAttemptResponse> open(@Valid @RequestBody OpenCaptureRequest request) {
        return ApiResponse.ok(PaymentAttemptResponse.from(prepareService.open(request.target(), request.money())));
    }
}
