package com.grandis.nova.payment.outbox;

import com.grandis.nova.common.outbox.OutboxAggregateType;

/**
 * 받는 쪽이 원장을 다시 읽을 대상. 아웃박스의 aggregate_type 에 이름 그대로 들어간다.
 * 결제 결과는 결제 대상(주문 · 응모)의 것이라 대상 서비스의 id 를 aggregateId 로 쓴다.
 */
public enum AggregateType implements OutboxAggregateType {
    ORDER
}
