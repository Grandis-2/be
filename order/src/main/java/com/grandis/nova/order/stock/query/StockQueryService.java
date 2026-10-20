package com.grandis.nova.order.stock.query;

import com.grandis.nova.order.stock.domain.model.StockLevel;
import com.grandis.nova.order.stock.domain.repository.StockReader;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 서비스 간 재고 조회(catalog 의 목록 품절 · 상세 남은 수량). 계약: contracts/order-internal.md "GET /internal/stocks".
 *
 * 트랜잭션 없이 잠그지 않는 PK 조회 한 문장으로 읽는다 — 화면 표시용이고, READ COMMITTED 라 트랜잭션이 더해 줄 것이 없으며,
 * 선언 쿼리라 저장소도 트랜잭션을 열지 않는다. 읽기를 더하거나 상속 메서드(findById 등)로 바꾸면 이 전제가 깨진다.
 * 옵션이 어느 상품 · 판매 방식인지는 보지 않는다: 재고 행이 없는 옵션(재고를 넣기 전 · 사전예약)은 결과에서 빠지고,
 * catalog 는 그것을 판매 불가로 읽는다.
 */
@Service
public class StockQueryService {

    private final StockReader stockReader;

    public StockQueryService(StockReader stockReader) {
        this.stockReader = stockReader;
    }

    /**
     * 행이 있는 옵션의 남은 수량, 요청 순서(같은 id 는 처음 자리). 계약은 0 이상이다 — 표의 CHECK(ck_inventory_capacity)가
     * 음수를 막지만, 그 제약에 기대지 않고 여기서도 0 으로 자른다.
     */
    public List<AvailableStock> findAvailable(List<UUID> optionIds) {
        List<UUID> distinct = List.copyOf(new LinkedHashSet<>(optionIds));
        Map<UUID, StockLevel> levels = stockReader.findByOptionIds(distinct).stream()
                .collect(Collectors.toMap(StockLevel::optionId, Function.identity()));
        return distinct.stream()
                .filter(levels::containsKey)
                .map(id -> new AvailableStock(id, Math.max(0, levels.get(id).available())))
                .toList();
    }

    public record AvailableStock(UUID optionId, int available) {
    }
}
