package com.grandis.nova.order.cart.api;

import com.grandis.nova.order.cart.CartView;
import com.grandis.nova.order.cart.domain.model.Unavailability;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** GET /api/v1/cart. 살 수 없는 줄도 싣고 purchasable=false 와 이유를 단다(명세 F-U-07). */
public record CartResponse(List<Item> items) {

    static CartResponse from(CartView view) {
        return new CartResponse(view.lines().stream().map(Item::from).toList());
    }

    /**
     * @param productId         옵션이 없어졌으면(unavailableReason=DISCONTINUED) null — 이름 · 가격 칸도 null
     * @param warrantyPrice     보증을 골랐고 지금도 제공하면 그 추가금, 아니면 0
     * @param lineAmount        (unitPrice + warrantyPrice) × quantity
     * @param unavailableReason 살 수 있으면 null. DISCONTINUED · HIDDEN · PAUSED · WARRANTY_UNAVAILABLE · OUT_OF_STOCK · INSUFFICIENT_STOCK 중 하나
     */
    public record Item(UUID cartItemId, UUID productId, UUID variantId, String productTitle, String optionTitle, String imageUrl,
                       boolean warranty, int quantity, BigDecimal unitPrice, BigDecimal warrantyPrice, BigDecimal lineAmount,
                       int availableQuantity, boolean purchasable, Unavailability unavailableReason) {

        static Item from(CartView.Line line) {
            return new Item(line.cartItemId(), line.productId(), line.variantId(), line.productTitle(), line.optionTitle(),
                    line.imageUrl(), line.warranty(), line.quantity(), line.unitPrice(), line.warrantyPrice(), line.lineAmount(),
                    line.availableQuantity(), line.purchasable(), line.unavailableReason());
        }
    }
}
