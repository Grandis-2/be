package com.grandis.nova.order.order.place;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 장바구니 주문으로 고른 줄 하나 — 지금 장바구니의 한 줄(옵션, 보증, 수량)과 같아야 한다. 가격은 사용자가 화면에서 본 값이고
 * 대조에만 쓴다(주문 금액은 catalog 의 지금 값으로 서버가 계산한다).
 *
 * @param expectedWarrantyPrice 보증을 골랐을 때 화면에서 본 보증가. 보증을 고르지 않았으면 쓰지 않는다
 */
public record CartSelection(UUID optionId, int quantity, boolean warranty, BigDecimal expectedUnitPrice,
                            BigDecimal expectedWarrantyPrice) {
}
