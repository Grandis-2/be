package com.grandis.nova.order.order.domain.exception;

/**
 * 예약 식별 칸(uq_order_preorder · uq_order_preorder_token)이 이미 쓰였다. 예약당 주문은 평생 하나다.
 *
 * 저장소가 제약 이름을 보고 이 예외로 바꾼다 — 호출하는 쪽이 DB 제약 이름 문자열에 기대지 않게.
 * 두 키 중 어느 쪽으로 알렸는지는 가리지 않는다(순서가 보장되지 않는다). 그래서 이 예외는 "그 예약의 주문이 있을 것" 이지
 * 확정이 아니다 — 다른 예약이 같은 UUID 를 쓴 경우(짝 어긋남)도 여기 든다. 부른 쪽이 예약 id 로 다시 읽어 확인한다.
 * 이 예외가 나면 호출자의 트랜잭션은 rollback-only 다. 기존 주문은 새 트랜잭션에서 조회한다.
 */
public class OrderAlreadyPlacedException extends RuntimeException {

    private final Long preorderId;

    public OrderAlreadyPlacedException(Long preorderId, Throwable cause) {
        super("이 예약의 주문이 이미 있다: preorderId=" + preorderId, cause);
        this.preorderId = preorderId;
    }

    public Long getPreorderId() {
        return preorderId;
    }
}
