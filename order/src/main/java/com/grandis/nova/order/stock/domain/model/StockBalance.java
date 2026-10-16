package com.grandis.nova.order.stock.domain.model;

import java.util.UUID;

/**
 * 한 옵션의 재고 장부와 주문 기준 수량을 한 SQL 문장(같은 시점)으로 맞댄 것. 재고를 바꾸는 쓰기는 모두 주문 · 드로우 회차 행과 같은
 * 트랜잭션이라, 같은 시점에 읽으면 정상일 때 유예 없이 같다.
 *
 * 주문 기준 수량(사전예약 주문 제외):
 * <ul>
 *   <li>확보 = 확보 상태(OrderStatus.stockHold RESERVED) 주문의 수량 + 반영 전 드로우 회차의 당첨 인원</li>
 *   <li>판매 = 판매 상태(SOLD) 주문의 수량 + 반환 표식 없이 취소된 주문의 수량(환불 완료 — 판매 되돌리기가 없다)</li>
 * </ul>
 *
 * @param productId  옵션의 상품. catalog 에 옵션 행이 없으면 null
 * @param registered 재고 행이 있는가. 없으면 장부 쪽 값(total · actual)은 0 이다
 */
public record StockBalance(UUID optionId, UUID productId, boolean registered, int total, int actualReserved, int actualSold,
                           long expectedReserved, long expectedSold) {

    public boolean matches() {
        return actualReserved == expectedReserved && actualSold == expectedSold;
    }
}
