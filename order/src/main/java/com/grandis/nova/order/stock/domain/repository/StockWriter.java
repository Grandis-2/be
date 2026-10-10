package com.grandis.nova.order.stock.domain.repository;

import com.grandis.nova.order.stock.domain.exception.StockAlreadyCreatedException;
import com.grandis.nova.order.stock.domain.model.StockLevel;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * 재고 쓰기 포트. {@link com.grandis.nova.order.stock.StockLedger} 만 쓴다(StockArchitectureTest 가 강제).
 * 쓰기는 바로 DB 에 반영된다 — 제약 위반이 커밋이 아니라 부른 자리에서 드러난다.
 */
public interface StockWriter {

    /**
     * 있는 행을 option_id 오름차순으로 잠그며 읽는다. 없는 옵션은 잠기지 않는다(READ COMMITTED 라 갭 잠금이 없다).
     * 잠금 순서는 주문 행 → 결제 행 → 재고 행이다.
     */
    List<StockLevel> lockByOptionIds(Collection<UUID> optionIds);

    /**
     * 확보 + 판매가 total 이하일 때만 총량을 바꾼다.
     *
     * @return 조건에 맞은 행 수(0 또는 1). 같은 값으로 바꿔도 1 이다 — 드라이버가 바뀐 행이 아니라 맞은 행을 센다(CLIENT_FOUND_ROWS)
     */
    int changeTotal(UUID optionId, int total, Instant now);

    /**
     * 가용 재고(총량 − 확보 − 판매)가 quantity 이상일 때만 확보를 quantity 만큼 늘린다.
     *
     * @return 조건에 맞은 행 수(0 또는 1). 0 이면 부족하거나 재고 행이 없다
     */
    int reserve(UUID optionId, int quantity, Instant now);

    /**
     * 확보가 quantity 이상일 때만 확보를 quantity 만큼 줄인다(미결제 취소의 반환).
     *
     * @return 조건에 맞은 행 수(0 또는 1)
     */
    int release(UUID optionId, int quantity, Instant now);

    /**
     * 확보 · 판매 0 인 새 행을 만든다.
     *
     * @throws StockAlreadyCreatedException 다른 트랜잭션이 같은 옵션의 행을 먼저 만들었다
     */
    void insert(UUID optionId, int total, Instant now);
}
