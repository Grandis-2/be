package com.grandis.nova.order.order.api;

import com.grandis.nova.common.web.ApiResponse;
import com.grandis.nova.order.order.place.PlaceResult;
import com.grandis.nova.order.order.place.PlaceOrderService;
// common:security 도입 시: com.grandis.nova.common.security.CurrentCustomerId 로 바꾼다.
import com.grandis.nova.order.web.CurrentCustomerId;
import com.grandis.nova.order.web.SessionHeader;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * 사용자 주문 생성. 조회(GET)는 같은 경로에 따로 둔다(NV-52).
 * /api/v1/preorders/** 는 ALB 가 preorder 로 보내므로 사전예약 주문도 이 경로로 받는다.
 */
@RestController
@RequestMapping(OrderController.BASE_PATH)
public class OrderController {

    static final String BASE_PATH = "/api/v1/orders";

    private final PlaceOrderService placeOrderService;

    public OrderController(PlaceOrderService placeOrderService) {
        this.placeOrderService = placeOrderService;
    }

    /**
     * 새로 만들면 201 + Location, 같은 예약의 주문이 이미 있으면 200 + 그 주문.
     *
     * 세션 토큰({@link SessionHeader})은 preorder 결제 가능 확인에 그대로 전달한다(사용자 토큰 전달, 받는 쪽이 검증).
     * 없으면 싣지 않고 preorder 의 401 을 그대로 돌려준다 — 지금 order 에는 토큰 검증 필터가 없어 여기서 판단하지 않는다.
     * required = false 는 preorder 의 같은 전달(PreorderCancelController)과 같다. true 로 두면 헤더 누락이 공통 처리기에서
     * 400 이 되어 "인증 없음" 의 의미가 틀어진다.
     */
    @PostMapping
    public ResponseEntity<ApiResponse<OrderResponse>> place(
            @CurrentCustomerId Long customerId,
            @RequestHeader(name = SessionHeader.NAME, required = false) String sessionToken,
            @Valid @RequestBody PlaceOrderRequest request) {
        PlaceResult result = placeOrderService.place(customerId, sessionToken, request.requirePreorderToken(),
                request.shipTo().toAddress());
        ApiResponse<OrderResponse> body = ApiResponse.ok(OrderResponse.of(result.order(), result.items()));
        if (!result.created()) {
            return ResponseEntity.ok(body);
        }
        return ResponseEntity.created(URI.create(BASE_PATH + "/" + result.order().orderToken().value())).body(body);
    }
}
