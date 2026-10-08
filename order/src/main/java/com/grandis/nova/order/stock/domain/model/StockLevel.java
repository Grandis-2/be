package com.grandis.nova.order.stock.domain.model;

import java.util.UUID;

/**
 * option_inventories 한 행. 칸이 셋인 이유는 취소 때 무엇을 되돌릴지 가리기 위해서다 — 미결제 취소는 확보, 결제된 주문 취소는 판매.
 *
 * @param total    관리자가 이 옵션에 배정한 총량(창고 실물이 아니다)
 * @param reserved 미결제 주문이 확보한 수량
 * @param sold     결제된 주문의 수량
 */
public record StockLevel(UUID optionId, int total, int reserved, int sold) {

    /** 총량을 이보다 작게 줄일 수 없다. */
    public int committed() {
        return reserved + sold;
    }

    public int available() {
        return total - committed();
    }
}
