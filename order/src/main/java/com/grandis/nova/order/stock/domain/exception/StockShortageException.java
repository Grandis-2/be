package com.grandis.nova.order.stock.domain.exception;

import java.util.List;
import java.util.UUID;

/**
 * 가용 재고(총량 − 확보 − 판매)가 확보하려는 수량보다 적다(재고 행이 없는 옵션 포함). 걸린 옵션을 모두 담는다.
 * 이 예외가 나면 호출자의 트랜잭션은 롤백해야 한다 — 같은 요청에서 먼저 확보한 옵션도 되돌아가야 "전량 확보 아니면 미생성" 이다.
 */
public class StockShortageException extends RuntimeException {

    private final List<UUID> optionIds;

    public StockShortageException(List<UUID> optionIds) {
        super("가용 재고 부족: " + optionIds);
        this.optionIds = List.copyOf(optionIds);
    }

    public List<UUID> optionIds() {
        return optionIds;
    }
}
