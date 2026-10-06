package com.grandis.nova.order.order.api;

import com.grandis.nova.common.security.CurrentCustomerId;
import com.grandis.nova.common.web.ApiResponse;
import com.grandis.nova.order.order.query.OrderQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 서비스 간 내부 API — 주문상품. catalog 가 회원의 리뷰 작성 때 그 회원의 토큰을 그대로 싣고(Authorization: Bearer) 부른다.
 * 회원(USER)만 받는다 — 관리자 토큰은 403. 계약: contracts/order-internal.md.
 */
@RestController
@RequestMapping("/internal/order-items")
public class InternalOrderItemController {

    private final OrderQueryService queryService;

    public InternalOrderItemController(OrderQueryService queryService) {
        this.queryService = queryService;
    }

    /** 토큰 주인의 주문상품과 그 주문의 상태 · 출처. 없거나 남의 것이면 404 ORDER_ITEM_NOT_FOUND. */
    @GetMapping("/{orderItemId}")
    public ApiResponse<InternalOrderItemResponse> find(@CurrentCustomerId Long customerId,
                                                       @PathVariable Long orderItemId) {
        return ApiResponse.ok(InternalOrderItemResponse.from(queryService.findItem(customerId, orderItemId)));
    }
}
