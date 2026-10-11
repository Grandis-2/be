package com.grandis.nova.order.cart.domain.model;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 장바구니 줄이 가리키는 옵션의 지금 사실(catalog 가 준 것). 도메인 판정에 쓰는 칸만 둔다.
 *
 * @param sellableStatus 상품 · 옵션 둘 다 판매 중(ACTIVE)이다
 * @param exposed        공개이고 판매 방식별 준비가 끝났다 — 아니면 회원에게 보이지 않는 상품이다
 * @param inStock        일반 판매(IN_STOCK) 상품이다 — 사전예약은 장바구니에 담지 않는다
 */
public record CartOption(UUID optionId, UUID productId, String productTitle, String optionTitle, String imageUrl,
                         BigDecimal price, boolean inStock, boolean exposed, boolean sellableStatus,
                         boolean warrantyOffered, BigDecimal warrantySurcharge) {
}
