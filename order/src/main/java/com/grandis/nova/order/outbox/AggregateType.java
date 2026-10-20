package com.grandis.nova.order.outbox;

import com.grandis.nova.common.outbox.OutboxAggregateType;

/**
 * 소비자가 원장을 다시 읽을 대상. 아웃박스의 aggregate_type 에 이름 그대로 들어간다.
 *
 * - PREORDER: 받는 쪽(preorder)의 예약. 주문 정리 결과에는 주문이 없는 경우(NO_ORDER)도 있어 주문 id 를 쓸 수 없다.
 * - ORDER: 주문. 환불 요청은 결제 대상(주문) id 로 payment 가 결제를 찾는다.
 * - DRAW_ENTRY · DRAW_CAMPAIGN: 드로우 응모(Mock 의 멱등 키가 응모 id) · 회차(추첨 요청).
 * - PRODUCT: catalog 의 상품(판매 준비). catalog 가 보내는 봉투와 같은 표기다.
 */
public enum AggregateType implements OutboxAggregateType {
    PREORDER,
    ORDER,
    DRAW_ENTRY,
    DRAW_CAMPAIGN,
    PRODUCT
}
