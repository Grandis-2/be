package com.grandis.nova.order.order.api;

import com.grandis.nova.common.CursorPage;
import com.grandis.nova.common.security.CurrentCustomerId;
import com.grandis.nova.common.web.ApiResponse;
import com.grandis.nova.order.order.query.OrderQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 내 주문 조회. 본인 것만 본다. 관리자는 회원 id 가 없어 "내 주문" 이 성립하지 않는다 —
 * {@link CurrentCustomerId} 가 ADMIN 을 403 으로 막는다. 관리자는 /api/v1/admin/orders 를 쓴다.
 *
 * 경로의 orderId 는 order_token 이다. 생성 응답의 Location 이 이 상세 경로를 가리킨다.
 */
@RestController
@RequestMapping("/api/v1/orders")
public class OrderQueryController {

    private final OrderQueryService queryService;

    public OrderQueryController(OrderQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping
    public ApiResponse<CursorPage<OrderResponse>> list(
            @CurrentCustomerId Long customerId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "" + PageSizes.DEFAULT) int size) {
        return ApiResponse.ok(queryService.findMine(customerId, cursor, PageSizes.require(size))
                .map(view -> OrderResponse.of(view.order(), view.items())));
    }

    @GetMapping("/{orderId}")
    public ApiResponse<OrderDetailResponse> get(@CurrentCustomerId Long customerId, @PathVariable String orderId) {
        return ApiResponse.ok(OrderDetailResponse.from(queryService.findOne(customerId, orderId)));
    }
}
