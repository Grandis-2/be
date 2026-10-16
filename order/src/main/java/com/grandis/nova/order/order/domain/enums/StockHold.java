package com.grandis.nova.order.order.domain.enums;

/**
 * 주문 상태가 재고 장부에서 쥐는 몫({@link OrderStatus#stockHold()}). 사전예약 주문은 재고를 두지 않아 상태와 무관하게 쥐지 않는다.
 * 재고 장부 대조가 이 분류로 주문 기준 수량을 센다 — 확보 = RESERVED 인 주문상품 수량의 합, 판매 = SOLD 인 주문상품 수량의 합.
 */
public enum StockHold {
    /** 확보(option_inventories.stock_reserved)에 든다 — 결제 전. */
    RESERVED,
    /** 판매(stock_sold)에 든다 — 결제 뒤. */
    SOLD,
    /**
     * 상태만으로는 아무것도 쥐지 않는다. 다만 판매된 뒤 들어온 주문(환불 완료)은 판매 되돌리기가 없어 판매에 남는다 — 그래서 대조는
     * 반환 표식(stock_released_at, 미결제 취소가 확보를 돌려준 표시)이 없으면 판매로 센다.
     */
    NONE
}
