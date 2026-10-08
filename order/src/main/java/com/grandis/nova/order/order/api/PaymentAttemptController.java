package com.grandis.nova.order.order.api;

import com.grandis.nova.common.security.BearerTokens;
import com.grandis.nova.common.security.CurrentCustomerId;
import com.grandis.nova.common.web.ApiResponse;
import com.grandis.nova.order.order.pay.ConfirmPaymentService;
import com.grandis.nova.order.order.pay.PreparePaymentService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 주문 결제. 결제창을 열기 전에 부른다(준비). 결제창 인증을 마치면 승인을 부른다.
 *
 * 액세스 토큰(Authorization: Bearer)은 preorder · payment 에 그대로 전달한다. 꺼내는 규칙은 인증 필터와 같은
 * {@link BearerTokens#extract} 다.
 */
@RestController
@RequestMapping("/api/v1/orders/{orderToken}/payment-attempts")
public class PaymentAttemptController {

    private final PreparePaymentService prepareService;
    private final ConfirmPaymentService confirmService;

    public PaymentAttemptController(PreparePaymentService prepareService, ConfirmPaymentService confirmService) {
        this.prepareService = prepareService;
        this.confirmService = confirmService;
    }

    /** 결제창을 열 값을 돌려준다. 본문은 없다 — 금액은 주문의 저장값이다. 부를 때마다 새 결제사 주문 번호다(결제창을 다시 열 때). */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<PaymentAttemptResponse> prepare(@CurrentCustomerId UUID customerId,
                                                       @PathVariable String orderToken,
                                                       HttpServletRequest httpRequest) {
        String accessToken = BearerTokens.extract(httpRequest).orElse(null);
        return ApiResponse.ok(PaymentAttemptResponse.of(prepareService.prepare(customerId, accessToken, orderToken)));
    }

    /**
     * 결제창 인증을 마친 결제를 승인한다. 결과는 200 이고 result 로 가른다(APPROVED · DECLINED · PENDING) — 응답 모양과
     * 프론트 처리(GET 폴링 · 승인 재요청 1회 · 503/500 의 뜻)는 {@link ConfirmPaymentResponse}.
     * 승인 전에 거절되면 오류 봉투다(금액 불일치 · 결제할 수 없는 주문 · 예약 등).
     */
    @PostMapping("/{tossOrderId}/confirm")
    public ApiResponse<ConfirmPaymentResponse> confirm(@CurrentCustomerId UUID customerId,
                                                       @PathVariable String orderToken,
                                                       @PathVariable @Pattern(regexp = "[A-Za-z0-9_-]{6,64}")
                                                       String tossOrderId,
                                                       @Valid @RequestBody ConfirmPaymentRequest request,
                                                       HttpServletRequest httpRequest) {
        String accessToken = BearerTokens.extract(httpRequest).orElse(null);
        return ApiResponse.ok(ConfirmPaymentResponse.of(confirmService.confirm(customerId, accessToken, orderToken,
                tossOrderId, request.paymentKey(), request.amount())));
    }
}
