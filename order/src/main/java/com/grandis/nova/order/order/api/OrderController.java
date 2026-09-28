package com.grandis.nova.order.order.api;

import com.grandis.nova.common.web.ApiResponse;
import com.grandis.nova.order.order.place.PlaceResult;
import com.grandis.nova.order.order.place.PlaceOrderService;
// common:security 도입 시: com.grandis.nova.common.security.CurrentCustomerId 로 바꾼다.
import com.grandis.nova.order.web.CurrentCustomerId;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    /** 새로 만들면 201 + Location, 같은 예약의 주문이 이미 있으면 200 + 그 주문. */
    @PostMapping
    public ResponseEntity<ApiResponse<OrderResponse>> place(@CurrentCustomerId Long customerId,
                                                            @Valid @RequestBody PlaceOrderRequest request) {
        PlaceResult result = placeOrderService.place(customerId, request.requirePreorderToken(),
                request.shipTo().toAddress());
        ApiResponse<OrderResponse> body = ApiResponse.ok(OrderResponse.of(result.order(), result.items()));
        if (!result.created()) {
            return ResponseEntity.ok(body);
        }
        return ResponseEntity.created(URI.create(BASE_PATH + "/" + result.order().orderToken().value())).body(body);
    }
}
