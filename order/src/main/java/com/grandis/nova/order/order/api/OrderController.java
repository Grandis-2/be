package com.grandis.nova.order.order.api;

import com.grandis.nova.common.security.BearerTokens;
import com.grandis.nova.common.security.CurrentCustomerId;
import com.grandis.nova.common.web.ApiResponse;
import com.grandis.nova.order.order.cancel.CancelCartOrderService;
import com.grandis.nova.order.order.place.PlaceCartOrderService;
import com.grandis.nova.order.order.place.PlaceResult;
import com.grandis.nova.order.order.place.PlaceOrderService;
import com.grandis.nova.order.web.ValidationFailures;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

/**
 * 사용자 주문 생성. 조회(GET)는 같은 경로에 따로 둔다(NV-52).
 * /api/v1/preorders/** 는 ALB 가 preorder 로 보내므로 사전예약 주문도 이 경로로 받는다.
 */
@RestController
@RequestMapping(OrderController.BASE_PATH)
public class OrderController {

    static final String BASE_PATH = "/api/v1/orders";

    private final PlaceOrderService placeOrderService;
    private final PlaceCartOrderService placeCartOrderService;
    private final CancelCartOrderService cancelCartOrderService;

    public OrderController(PlaceOrderService placeOrderService, PlaceCartOrderService placeCartOrderService,
                           CancelCartOrderService cancelCartOrderService) {
        this.placeOrderService = placeOrderService;
        this.placeCartOrderService = placeCartOrderService;
        this.cancelCartOrderService = cancelCartOrderService;
    }

    /**
     * 미결제 장바구니 주문 취소(명세 POST /orders/{orderId}/cancel). 결제 대기면 그 자리에서 취소 · 재고 반환(200), 이미 취소됐으면 같은 200.
     * 승인 중이면 409 PAYMENT_IN_PROGRESS, 결제된 주문 · 사전예약 주문은 409 STATE_CONFLICT. 남의 주문은 404.
     */
    @PostMapping("/{orderToken}/cancel")
    public ApiResponse<CancelOrderResponse> cancel(@CurrentCustomerId UUID customerId, @PathVariable String orderToken) {
        return ApiResponse.ok(CancelOrderResponse.of(cancelCartOrderService.cancel(customerId, orderToken)));
    }

    /**
     * 새로 만들면 201 + Location, 같은 예약의 주문 · 같은 구성의 유효한 장바구니 주문이 이미 있으면 200 + 그 주문.
     *
     * 액세스 토큰(Authorization: Bearer)은 preorder 결제 가능 확인 · catalog 옵션 조회에 그대로 전달한다(사용자 토큰 전달, 받는 쪽이 다시 검증).
     * 꺼내는 규칙은 인증 필터와 같은 {@link BearerTokens#extract} 다. 인증을 통과했다면 토큰이 있다 — 없으면 싣지 않고
     * preorder 의 401 을 그대로 돌려준다.
     */
    @PostMapping
    public ResponseEntity<ApiResponse<PlaceOrderResponse>> place(
            @CurrentCustomerId UUID customerId,
            @Valid @RequestBody PlaceOrderRequest request,
            HttpServletRequest httpRequest) {
        String accessToken = BearerTokens.extract(httpRequest).orElse(null);
        PlaceResult result = switch (request.source()) {
            case PREORDER -> placeOrderService.place(customerId, accessToken, request.requirePreorderToken(),
                    request.shipTo().toAddress());
            case CART -> placeCartOrderService.place(customerId, accessToken, request.requireCartSelections(),
                    request.shipTo().toAddress());
            case BUY_NOW -> throw ValidationFailures.of("source", "바로 구매 주문은 아직 받지 않습니다.");
        };
        ApiResponse<PlaceOrderResponse> body = ApiResponse.ok(PlaceOrderResponse.of(result));
        if (!result.created()) {
            return ResponseEntity.ok(body);
        }
        return ResponseEntity.created(URI.create(BASE_PATH + "/" + result.order().orderToken().value())).body(body);
    }
}
