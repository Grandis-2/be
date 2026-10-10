package com.grandis.nova.order.order.api;

import com.grandis.nova.order.order.command.PlaceOrderCommand;
import com.grandis.nova.order.order.domain.enums.OrderSource;
import com.grandis.nova.order.order.place.CartSelection;
import com.grandis.nova.order.web.ValidationFailures;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * 주문 생성 요청. 경로는 하나이고 source 로 가른다 — PREORDER(preorderId) · CART(items). BUY_NOW 는 아직 받지 않는다.
 * 금액 칸이 없다. 금액은 서버가 계산한다(사전예약은 예약 스냅샷, 장바구니는 catalog 의 지금 값). 장바구니 줄의 기대 가격은 대조에만 쓴다.
 *
 * 배송지 길이 규칙은 {@link com.grandis.nova.order.order.vo.ShipTo} 와 같다. VO 가 던지는 IllegalArgumentException 은
 * 500 이 되므로 여기서 400 으로 먼저 막는다.
 *
 * @param preorderId 예약 공개 토큰(소문자 UUID). source=PREORDER 면 필수
 * @param items      고른 장바구니 줄(1~50). source=CART 면 필수 — 지금 장바구니의 줄과 (옵션, 보증, 수량)이 같아야 한다
 */
public record PlaceOrderRequest(
        @NotNull OrderSource source,
        @Pattern(regexp = UUID_PATTERN, message = "예약 토큰 형식이 아닙니다.") String preorderId,
        @Size(max = 50) List<@NotNull @Valid CartItem> items,
        @NotNull @Valid ShipTo shipTo
) {

    static final String UUID_PATTERN = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$";

    /** 사전예약 주문의 예약 토큰. 없거나 장바구니 칸(items)이 같이 오면 400. */
    String requirePreorderToken() {
        if (items != null) {
            throw ValidationFailures.of("items", "사전예약 주문에는 보낼 수 없습니다.");
        }
        if (preorderId == null) {
            throw ValidationFailures.of("preorderId", "필수 항목입니다.");
        }
        return preorderId;
    }

    /** 장바구니 주문의 고른 줄. 없거나 비었거나 예약 토큰이 같이 오면 400. */
    List<CartSelection> requireCartSelections() {
        if (preorderId != null) {
            throw ValidationFailures.of("preorderId", "장바구니 주문에는 보낼 수 없습니다.");
        }
        if (items == null || items.isEmpty()) {
            throw ValidationFailures.of("items", "필수 항목입니다.");
        }
        return items.stream().map(CartItem::toSelection).toList();
    }

    /**
     * 장바구니 주문의 한 줄. 가격은 화면에서 본 값이다 — 지금 값과 다르면 409 PRICE_CHANGED.
     *
     * @param warranty              보증 포함 줄인가(없으면 false)
     * @param expectedWarrantyPrice 보증 포함 줄이면 필수
     */
    public record CartItem(
            @NotNull UUID variantId,
            @NotNull @Min(1) @Max(99) Integer quantity,
            Boolean warranty,
            @NotNull @PositiveOrZero BigDecimal expectedUnitPrice,
            @PositiveOrZero BigDecimal expectedWarrantyPrice
    ) {

        CartSelection toSelection() {
            boolean selected = Boolean.TRUE.equals(warranty);
            if (selected && expectedWarrantyPrice == null) {
                throw ValidationFailures.of("items.expectedWarrantyPrice", "보증 포함 줄은 필수 항목입니다.");
            }
            return new CartSelection(variantId, quantity, selected, expectedUnitPrice, expectedWarrantyPrice);
        }
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
