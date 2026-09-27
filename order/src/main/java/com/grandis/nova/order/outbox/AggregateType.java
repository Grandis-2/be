package com.grandis.nova.order.outbox;

/**
 * 소비자가 원장을 다시 읽을 대상. outbox_events.aggregate_type 에 이름 그대로 들어간다.
 *
 * PREORDER 는 받는 쪽(preorder)의 예약이다. 주문 정리 결과에는 주문이 없는 경우(NO_ORDER)도 있어 주문 id 를 쓸 수 없다.
 *
 * common:outbox 이전 시: 모듈마다 값이 달라 공통 쪽은 인터페이스(name)로 받고, 이 enum 이 그것을 구현한다.
 */
public enum AggregateType {
    PREORDER
}
