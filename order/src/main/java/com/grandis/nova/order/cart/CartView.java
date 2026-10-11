package com.grandis.nova.order.cart;

import com.grandis.nova.order.cart.domain.model.Unavailability;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * 장바구니 조회 결과. 줄마다 catalog 의 지금 값과 재고를 붙이고, 살 수 없으면 이유를 하나 단다. 살 수 없는 줄도 지우지 않는다(명세 F-U-07).
 */
public record CartView(List<Line> lines) {

    /**
     * @param productId         옵션이 없어졌으면(DISCONTINUED) null — 이름 · 가격 칸도 null 이다
     * @param unitPrice         옵션의 지금 가격(정수 원)
     * @param warrantyPrice     보증을 골랐으면 보증 추가금, 아니면 0
     * @param lineAmount        (unitPrice + warrantyPrice) × quantity
     * @param availableQuantity 지금 가용 재고(재고 행이 없으면 0)
     */
    public record Line(UUID cartItemId, UUID variantId, UUID productId, String productTitle, String optionTitle,
                       String imageUrl, boolean warranty, int quantity, BigDecimal unitPrice, BigDecimal warrantyPrice,
                       BigDecimal lineAmount, int availableQuantity, Unavailability unavailableReason) {

        public boolean purchasable() {
            return unavailableReason == null;
        }
    }
}
