package com.grandis.nova.order.outbox;

import com.grandis.nova.common.outbox.OutboxAggregateType;

/**
 * 소비자가 원장을 다시 읽을 대상. 아웃박스의 aggregate_type 에 이름 그대로 들어간다.
 *
 * PREORDER 는 받는 쪽(preorder)의 예약이다. 주문 정리 결과에는 주문이 없는 경우(NO_ORDER)도 있어 주문 id 를 쓸 수 없다.
 */
public enum AggregateType implements OutboxAggregateType {
    PREORDER
}
