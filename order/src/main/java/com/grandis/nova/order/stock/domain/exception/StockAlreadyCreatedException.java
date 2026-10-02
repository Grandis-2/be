package com.grandis.nova.order.stock.domain.exception;

/**
 * 없다고 본 재고 행을 다른 트랜잭션이 먼저 만들었다(PK 중복). 저장소가 제약 이름을 보고 이 예외로 바꾼다.
 * 호출자의 트랜잭션은 rollback-only 다. 새 트랜잭션에서 다시 하면 그 행은 "있는 행"으로 잡힌다.
 */
public class StockAlreadyCreatedException extends RuntimeException {

    public StockAlreadyCreatedException(Long optionId, Throwable cause) {
        super("재고 행이 이미 있다: optionId=" + optionId, cause);
    }
}
