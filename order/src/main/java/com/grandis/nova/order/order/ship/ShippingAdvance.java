package com.grandis.nova.order.order.ship;

import com.grandis.nova.order.order.domain.enums.OrderStatus;
import com.grandis.nova.order.order.vo.OrderToken;

/**
 * @param applied 이번 요청이 옮겼으면 true. 이미 그 단계였으면 false(이력은 먼저 옮긴 요청의 사유다)
 */
public record ShippingAdvance(OrderToken orderToken, OrderStatus status, boolean applied) {
}
