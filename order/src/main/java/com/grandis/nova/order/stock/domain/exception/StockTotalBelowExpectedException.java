package com.grandis.nova.order.stock.domain.exception;

import java.util.UUID;

/**
 * 주문 기준 확보 + 판매가 총량을 넘어 보정할 수 없다(재고 행이 없으면 총량 0). 관리자가 총량을 먼저 늘려야 한다.
 * 이 예외가 나면 호출자의 트랜잭션은 롤백해야 한다.
 */
public class StockTotalBelowExpectedException extends RuntimeException {

    private final UUID optionId;
    private final boolean registered;
    private final int total;
    private final long expectedReserved;
    private final long expectedSold;

    public StockTotalBelowExpectedException(UUID optionId, boolean registered, int total, long expectedReserved, long expectedSold) {
        super("주문 기준 수량이 총량을 넘는다: optionId=%s, registered=%s, total=%d, expectedReserved=%d, expectedSold=%d"
                .formatted(optionId, registered, total, expectedReserved, expectedSold));
        this.optionId = optionId;
        this.registered = registered;
        this.total = total;
        this.expectedReserved = expectedReserved;
        this.expectedSold = expectedSold;
    }

    public UUID optionId() {
        return optionId;
    }

    public boolean registered() {
        return registered;
    }

    public int total() {
        return total;
    }

    public long expectedReserved() {
        return expectedReserved;
    }

    public long expectedSold() {
        return expectedSold;
    }
}
