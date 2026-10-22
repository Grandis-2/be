package com.grandis.nova.order.order.api;

import com.grandis.nova.common.CursorPage;
import com.grandis.nova.common.security.CurrentCustomerId;
import com.grandis.nova.common.web.ApiResponse;
import com.grandis.nova.order.order.domain.enums.OrderStatus;
import com.grandis.nova.order.order.domain.repository.CustomerOrderFilter;
import com.grandis.nova.order.order.query.OrderQueryService;
import com.grandis.nova.order.web.PageSizes;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * 내 주문 조회. 본인 것만 본다. 관리자는 회원 id 가 없어 "내 주문" 이 성립하지 않는다 —
 * {@link CurrentCustomerId} 가 ADMIN 을 403 으로 막는다. 관리자는 /api/v1/admin/orders 를 쓴다.
 *
 * 경로의 orderId 는 orders.id 다. 생성 응답의 Location 이 이 상세 경로를 가리킨다.
 */
@RestController
@RequestMapping("/api/v1/orders")
public class OrderQueryController {

    private final OrderQueryService queryService;

    public OrderQueryController(OrderQueryService queryService) {
        this.queryService = queryService;
    }

    /**
     * 최신순. status 는 여러 번 줄 수 있고(그중 하나), from 은 그 시각 이후 주문이다. counts 는 이 조건과 상관없이 내 주문 전부의 상태별 수다.
     * 모르는 status 는 400.
     */
    @GetMapping
    public ApiResponse<MyOrdersResponse> list(
            @CurrentCustomerId UUID customerId,
            @RequestParam(required = false) Set<OrderStatus> status,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "" + PageSizes.DEFAULT) int size) {
        CustomerOrderFilter filter = new CustomerOrderFilter(status, CreatedAtBounds.of("from", from));
        CursorPage<OrderResponse> page = queryService.findMine(customerId, filter, cursor, PageSizes.require(size))
                .map(view -> OrderResponse.of(view.order(), view.items()));
        return ApiResponse.ok(MyOrdersResponse.of(page, queryService.countMine(customerId)));
    }

    @GetMapping("/{orderId}")
    public ApiResponse<OrderDetailResponse> get(@CurrentCustomerId UUID customerId, @PathVariable String orderId) {
        return ApiResponse.ok(OrderDetailResponse.from(queryService.findOne(customerId, orderId)));
    }
}
