package com.grandis.nova.order.stock.domain.enums;

/** products.sale_mode. 재고 행은 일반 판매(IN_STOCK) 상품의 옵션에만 둔다. 사전예약은 선점이 없어 재고를 세지 않는다. */
public enum SaleMode {
    PREORDER,
    IN_STOCK
}
