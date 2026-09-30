package com.grandis.nova.order.order.vo;

/** 주문 수량. 1 이상이다(ck_order_item_quantity). */
public record Quantity(int value) {

    public static final Quantity ONE = new Quantity(1);

    public Quantity {
        if (value <= 0) {
            throw new IllegalArgumentException("수량은 1 이상이어야 한다: " + value);
        }
    }
}
