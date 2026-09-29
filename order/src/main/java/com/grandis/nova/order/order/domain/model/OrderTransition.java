package com.grandis.nova.order.order.domain.model;

import com.grandis.nova.order.order.domain.enums.OrderStatus;

/**
 * 사건을 적용한 결과.
 *
 * applied = false 는 전제와 다른 상태 · 중복 수신 · 받아들일 수 없는 사건 · 결과 대기 중 하나다. 원장은 구분하지 않고
 * 지금 상태만 돌려준다. applied 만 보고 "처리 완료" 로 답하면 출고된 주문을 취소된 것으로 알리게 된다 —
 * 호출하는 쪽은 반드시 status 를 본다.
 *
 * @param applied 상태가 바뀌었으면 true
 * @param status  적용 후(바뀌지 않았으면 지금) 상태
 */
public record OrderTransition(boolean applied, OrderStatus status) {
}
