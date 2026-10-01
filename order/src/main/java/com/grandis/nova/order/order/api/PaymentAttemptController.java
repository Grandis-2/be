package com.grandis.nova.order.order.api;

import com.grandis.nova.common.security.BearerTokens;
import com.grandis.nova.common.security.CurrentCustomerId;
import com.grandis.nova.common.web.ApiResponse;
import com.grandis.nova.order.order.pay.PreparePaymentService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 주문 결제. 결제창을 열기 전에 부른다(준비). 승인은 같은 경로 아래에 따로 둔다(NV-101).
 */
@RestController
@RequestMapping("/api/v1/orders/{orderToken}/payment-attempts")
public class PaymentAttemptController {

    private final PreparePaymentService prepareService;

    public PaymentAttemptController(PreparePaymentService prepareService) {
        this.prepareService = prepareService;
    }

    /**
     * 결제창을 열 값을 돌려준다. 본문은 없다 — 금액은 주문의 저장값이다. 부를 때마다 새 결제사 주문 번호다(결제창을 다시 열 때).
     *
     * 액세스 토큰(Authorization: Bearer)은 preorder · payment 에 그대로 전달한다. 꺼내는 규칙은 인증 필터와 같은
     * {@link BearerTokens#extract} 다.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<PaymentAttemptResponse> prepare(@CurrentCustomerId Long customerId,
                                                       @PathVariable String orderToken,
                                                       HttpServletRequest httpRequest) {
        String accessToken = BearerTokens.extract(httpRequest).orElse(null);
        return ApiResponse.ok(PaymentAttemptResponse.of(prepareService.prepare(customerId, accessToken, orderToken)));
    }
}
