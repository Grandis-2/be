package com.grandis.nova.order.order.domain.model;

import com.grandis.nova.order.order.vo.Money;
import com.grandis.nova.order.order.vo.Quantity;

import java.util.Objects;
import java.util.UUID;

/**
 * 주문할 상품 한 줄(옵션 하나). 가격 · 이름은 주문 시점의 스냅샷이다 — 사전예약은 예약 접수 때, 장바구니는 주문 때 catalog 값.
 * 저장되면 {@link OrderItem} 이 이것을 그대로 들고 있다.
 *
 * 같은 옵션은 한 줄이다(uq_order_item_option) — 장바구니의 보증 포함 · 미포함 두 줄은 여기서 합쳐, 그중 보증을 산 수량과 보증가를 둔다.
 * 보증을 사지 않았으면 보증 수량 0 · 보증가 0 이다(ck_order_item_warranty_quantity · ck_order_item_warranty_price).
 *
 * 이름 길이는 DB 칸(product_title_snapshot 100 · option_title_snapshot 120)과 같다. MySQL 은 글자 수로 세므로
 * 코드 포인트로 센다.
 */
public record OrderLine(
        UUID productId,
        UUID optionId,
        Quantity quantity,
        Money unitPrice,
        int warrantyQuantity,
        Money warrantyUnitPrice,
        String productTitle,
        String optionTitle
) {

    public OrderLine {
        Objects.requireNonNull(productId, "productId");
        Objects.requireNonNull(optionId, "optionId");
        Objects.requireNonNull(quantity, "quantity");
        Objects.requireNonNull(unitPrice, "unitPrice");
        Objects.requireNonNull(warrantyUnitPrice, "warrantyUnitPrice");
        if (warrantyQuantity < 0 || warrantyQuantity > quantity.value()) {
            throw new IllegalArgumentException("보증 수량은 0 이상 수량 이하다: " + warrantyQuantity + " / " + quantity.value());
        }
        if (warrantyQuantity == 0 && warrantyUnitPrice.amount().signum() != 0) {
            throw new IllegalArgumentException("보증을 사지 않은 줄의 보증가는 0 이다");
        }
        requireLength(productTitle, "productTitle", 100);
        requireLength(optionTitle, "optionTitle", 120);
    }

    /** 사전예약 · 보증 없는 줄. */
    public static OrderLine withoutWarranty(UUID productId, UUID optionId, Quantity quantity, Money unitPrice,
                                            String productTitle, String optionTitle) {
        return new OrderLine(productId, optionId, quantity, unitPrice, 0, Money.ZERO, productTitle, optionTitle);
    }

    /** 줄 금액 = 단가 × 수량 + 보증가 × 보증 수량. */
    public Money subtotal() {
        Money items = unitPrice.times(quantity);
        return warrantyQuantity == 0 ? items : items.plus(warrantyUnitPrice.times(new Quantity(warrantyQuantity)));
    }

    private static void requireLength(String value, String field, int maxLength) {
        Objects.requireNonNull(value, field);
        if (value.codePointCount(0, value.length()) > maxLength) {
            throw new IllegalArgumentException("%s 는 %d자 이하여야 한다".formatted(field, maxLength));
        }
    }
}
