package com.grandis.nova.preorder.integration.order;

/**
 * order 의 취소 가능 판정. 배송(SHIPPED) 이후면 cancelable = false.
 *
 * @param orderStatus 주문이 없으면 null
 */
public record Cancelability(String preorderId, String orderStatus, boolean cancelable, String reason) {
}
