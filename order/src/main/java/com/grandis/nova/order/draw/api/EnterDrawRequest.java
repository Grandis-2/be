package com.grandis.nova.order.draw.api;

import com.grandis.nova.order.order.api.PlaceOrderRequest;
import com.grandis.nova.order.order.vo.ShipTo;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * 응모 요청. 배송지만 받는다 — 응모비는 회차의 값이다. 배송지 규칙은 주문과 같다({@link PlaceOrderRequest.ShipTo}).
 * 당첨되면 이 배송지로 증정한다.
 */
public record EnterDrawRequest(@NotNull @Valid PlaceOrderRequest.ShipTo shipTo) {

    ShipTo toShipTo() {
        return new ShipTo(shipTo.name(), shipTo.phone(), shipTo.postalCode(), shipTo.line1(), shipTo.line2());
    }
}
