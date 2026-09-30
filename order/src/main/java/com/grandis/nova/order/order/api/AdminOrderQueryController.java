package com.grandis.nova.order.order.api;

import com.grandis.nova.common.OffsetPage;
import com.grandis.nova.common.web.ApiResponse;
import com.grandis.nova.order.order.domain.enums.OrderSource;
import com.grandis.nova.order.order.domain.enums.OrderStatus;
import com.grandis.nova.order.order.domain.repository.AdminOrderFilter;
import com.grandis.nova.order.order.query.OrderQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 관리자 주문 조회. 권한은 보안 설정이 경로로 막는다(ADMIN). */
@RestController
@RequestMapping("/api/v1/admin/orders")
public class AdminOrderQueryController {

    private final OrderQueryService queryService;

    public AdminOrderQueryController(OrderQueryService queryService) {
        this.queryService = queryService;
    }

    /** 상태 · 주문 경로로 거른다. 전량을 내려주지 않는다. */
    @GetMapping
    public ApiResponse<OffsetPage<AdminOrderSummaryResponse>> list(
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(required = false) OrderSource source,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + PageSizes.DEFAULT) int size) {
        int checkedSize = PageSizes.require(size);
        return ApiResponse.ok(queryService
                .findForAdmin(new AdminOrderFilter(status, source), PageSizes.requirePage(page, checkedSize),
                        checkedSize)
                .map(AdminOrderSummaryResponse::from));
    }

    @GetMapping("/{orderId}")
    public ApiResponse<AdminOrderDetailResponse> get(@PathVariable String orderId) {
        return ApiResponse.ok(AdminOrderDetailResponse.from(queryService.findOneForAdmin(orderId)));
    }
}
