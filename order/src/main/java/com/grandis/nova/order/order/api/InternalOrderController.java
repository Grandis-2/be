package com.grandis.nova.order.order.api;

import com.grandis.nova.common.web.ApiResponse;
import com.grandis.nova.order.order.query.OrderCancelabilityQuery;
import com.grandis.nova.order.web.CurrentViewer;
import com.grandis.nova.order.web.Viewer;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 서비스 간 내부 API. ALB 를 거치지 않는다. 호출자는 받은 사용자 토큰을 그대로 싣고(Authorization: Bearer), 여기서 토큰 주인이
 * 주문 회원인지 확인한다(관리자 통과). 인증은 보안 설정이 경로로 요구한다(/internal/**).
 *
 * 경로의 preorderInternalId 는 예약 내부 id(preorders.id = orders.preorder_id)다. 형식이 틀리면 400.
 */
@RestController
@RequestMapping("/internal/orders")
public class InternalOrderController {

    private final OrderCancelabilityQuery cancelabilityQuery;

    public InternalOrderController(OrderCancelabilityQuery cancelabilityQuery) {
        this.cancelabilityQuery = cancelabilityQuery;
    }

    /** 예약 취소를 시작해도 되는가. 주문이 없으면 가능, 출고 뒤(SHIPPED · DELIVERED)면 불가. */
    @GetMapping("/by-preorder/{preorderInternalId}/cancelability")
    public ApiResponse<CancelabilityResponse> cancelability(@CurrentViewer Viewer viewer,
                                                            @PathVariable Long preorderInternalId) {
        return ApiResponse.ok(CancelabilityResponse.from(cancelabilityQuery.check(viewer, preorderInternalId)));
    }
}
