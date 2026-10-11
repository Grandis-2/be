package com.grandis.nova.order.stock.domain.exception;

import java.util.UUID;

/**
 * 주문이 확보해 둔 수량을 반환하려는데 그 옵션의 확보가 모자란다 — 재고 장부가 어긋난 것이다(정상 경로로는 결제 대기 주문의 확보가 줄지 않는다).
 * 걸린 옵션 하나와 그 수량을 싣는다 — 앞 옵션들은 이미 뺐다가 롤백되므로, 복구에 쓸 숫자는 이 옵션의 것만이다. 호출자의 트랜잭션은 롤백해야 한다.
 */
public class StockReleaseMismatchException extends IllegalStateException {

    private final UUID optionId;
    private final int quantity;

    public StockReleaseMismatchException(UUID optionId, int quantity) {
        super("반환할 확보가 모자란다: optionId=" + optionId + ", quantity=" + quantity);
        this.optionId = optionId;
        this.quantity = quantity;
    }

    public UUID optionId() {
        return optionId;
    }

    public int quantity() {
        return quantity;
    }
}
