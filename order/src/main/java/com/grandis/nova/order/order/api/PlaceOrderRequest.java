package com.grandis.nova.order.order.api;

import com.grandis.nova.order.order.command.PlaceOrderCommand;
import com.grandis.nova.order.order.domain.enums.OrderSource;
import com.grandis.nova.order.web.ValidationFailures;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 주문 생성 요청. 경로는 하나이고 source 로 가른다 — 지금은 PREORDER 만 받는다.
 * 금액 칸이 없다. 금액은 서버가 예약 스냅샷에서 계산한다.
 *
 * 배송지 길이 규칙은 {@link com.grandis.nova.order.order.vo.ShipTo} 와 같다. VO 가 던지는 IllegalArgumentException 은
 * 500 이 되므로 여기서 400 으로 먼저 막는다.
 *
 * @param preorderId 예약 공개 토큰(소문자 UUID). source=PREORDER 면 필수
 */
public record PlaceOrderRequest(
        @NotNull OrderSource source,
        @Pattern(regexp = UUID_PATTERN, message = "예약 토큰 형식이 아닙니다.") String preorderId,
        @NotNull @Valid ShipTo shipTo
) {

    static final String UUID_PATTERN = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$";

    /** 사전예약 주문의 예약 토큰. 다른 source 거나 토큰이 없으면 400. */
    String requirePreorderToken() {
        if (source != OrderSource.PREORDER) {
            throw ValidationFailures.of("source", "지금은 PREORDER 주문만 받습니다.");
        }
        if (preorderId == null) {
            throw ValidationFailures.of("preorderId", "필수 항목입니다.");
        }
        return preorderId;
    }

    public record ShipTo(
            @NotBlank @Size(max = 50) String name,
            @NotBlank @Size(max = 20) String phone,
            @NotBlank @Size(max = 10) String postalCode,
            @NotBlank @Size(max = 200) String line1,
            @Size(max = 200) String line2
    ) {

        /** 배송지는 개인정보라 toString 에 값을 싣지 않는다. */
        @Override
        public String toString() {
            return "ShipTo[***]";
        }

        PlaceOrderCommand.Address toAddress() {
            return new PlaceOrderCommand.Address(name, phone, postalCode, line1, line2);
        }
    }
}
