package com.grandis.nova.order.order.api;

import com.grandis.nova.common.web.ApiResponse;
import com.grandis.nova.order.order.ship.AdvanceShippingService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 배송 단계. 권한은 보안 설정이 경로로 막는다(ADMIN). 결제된 주문을 한 번에 한 단계씩 옮긴다:
 * 준비 시작 → 포장 완료 → 출고 → 배송 완료.
 *
 * 옮겼거나 이미 그 단계면 200(applied 로 가른다), 지금 상태에서 그 단계로 갈 수 없으면 409 와 details.status 다.
 */
@RestController
public class AdminShippingController {

    private final AdvanceShippingService service;

    public AdminShippingController(AdvanceShippingService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/admin/orders/{orderId}/shipping-steps")
    public ApiResponse<ShippingStepResponse> advance(@PathVariable String orderId,
                                                     @Valid @RequestBody AdvanceShippingRequest request) {
        return ApiResponse.ok(ShippingStepResponse.of(service.advance(orderId, request.step(), request.toCause())));
    }
}
