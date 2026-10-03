package com.grandis.nova.payment.api;

import com.grandis.nova.common.web.ApiResponse;
import com.grandis.nova.payment.confirm.ConfirmPaymentService;
import com.grandis.nova.payment.prepare.PreparePaymentService;
import com.grandis.nova.payment.vo.ProviderOrderId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
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
    private final ConfirmPaymentService confirmService;

    public InternalPaymentAttemptController(PreparePaymentService prepareService, ConfirmPaymentService confirmService) {
        this.prepareService = prepareService;
        this.confirmService = confirmService;
    }

    /** 결제창을 열 CAPTURE(PENDING)를 새로 만든다. 부를 때마다 새 거래다. 금액은 승인 때 다시 대조한다({@link OpenCaptureRequest}). */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<PaymentAttemptResponse> open(@Valid @RequestBody OpenCaptureRequest request) {
        return ApiResponse.ok(PaymentAttemptResponse.from(prepareService.open(request.target(), request.money())));
    }

    /**
     * 결제창 인증을 마친 결제를 승인한다. 시작했으면 늘 200 이고 결과는 APPROVED · DECLINED · PENDING 이다. 시작 전 거절은
     * 업무 오류(PaymentErrorCode)이고, 되돌려도 되는 것은 그중 일부다(그 javadoc). 같은 결제창 번호로 다시 부르면 토스를 부르지 않고
     * 그 거래의 지금 결과를 돌려준다.
     */
    @PostMapping("/{providerOrderId}/confirm")
    public ApiResponse<ConfirmResponse> confirm(@PathVariable @Pattern(regexp = "[A-Za-z0-9_-]{6,64}") String providerOrderId,
                                                @Valid @RequestBody ConfirmRequest request) {
        return ApiResponse.ok(ConfirmResponse.from(confirmService.confirm(new ProviderOrderId(providerOrderId),
                request.target(), request.providerPaymentKey(), request.money(), request.startAllowed())));
    }
}
