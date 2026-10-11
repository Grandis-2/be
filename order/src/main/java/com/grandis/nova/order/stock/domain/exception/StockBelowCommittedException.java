package com.grandis.nova.order.stock.domain.exception;

import java.util.List;
import java.util.UUID;

/**
 * 확보 + 판매보다 작게 총량을 줄이려 했다. 걸린 옵션을 모두 담는다 — 관리자가 하나씩 고치며 다시 거절당하지 않게.
 * 이 예외가 나면 호출자의 트랜잭션은 롤백해야 한다. 같은 요청의 다른 옵션도 바뀌지 않는다.
 */
public class StockBelowCommittedException extends RuntimeException {

    private final List<Shortfall> shortfalls;

    public StockBelowCommittedException(List<Shortfall> shortfalls) {
        super("확보 + 판매보다 작은 총량: " + shortfalls);
        this.shortfalls = List.copyOf(shortfalls);
    }

    public List<Shortfall> shortfalls() {
        return shortfalls;
    }

    /** @param committed 줄일 수 있는 하한(확보 + 판매) */
    public record Shortfall(UUID optionId, int committed) {
    }
}
