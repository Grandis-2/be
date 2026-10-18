package com.grandis.nova.catalog.integration;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 옵션별 남은 수량을 order 에 묻는다({@link StockClient}). 한 번에 {@value #BATCH}개씩 나눠 부른다(계약 상한). DB 트랜잭션 밖에서 부른다 — 응답을 기다리는
 * 동안 연결을 붙잡지 않게.
 */
@Component
public class StockReader {

    /** 한 번에 묻는 옵션 수 — 계약 상한(URL 길이로 정함). */
    static final int BATCH = 100;

    private final StockClient client;

    public StockReader(StockClient client) {
        this.client = client;
    }

    /**
     * @return 옵션 id → 남은 수량. 재고 행이 없는 옵션은 빠진다(판매 불가로 본다). 빈 묶음이면 부르지 않는다
     * @throws com.grandis.nova.common.BusinessException DEPENDENCY_UNAVAILABLE — 연결 실패 · 시간 초과 · 5xx. INTERNAL_ERROR — 연동 오류(그 밖의 4xx ·
     *                                                   읽을 수 없는 응답 · 묻지 않은 옵션 · 빈 수량, ERROR 로그를 남긴다 — {@link InternalCalls})
     */
    public Map<UUID, Integer> available(Collection<UUID> optionIds) {
        Map<UUID, Integer> available = new HashMap<>();
        List<UUID> ids = new ArrayList<>(new LinkedHashSet<>(optionIds));
        for (int from = 0; from < ids.size(); from += BATCH) {
            List<UUID> batch = ids.subList(from, Math.min(ids.size(), from + BATCH));
            StockClient.Stocks stocks = InternalCalls.call(Dependencies.ORDER_STOCKS, () -> client.getStocks(batch));
            for (StockClient.Stock stock : stocks.items() == null ? List.<StockClient.Stock>of() : stocks.items()) {
                if (stock.optionId() == null || stock.available() == null || !batch.contains(stock.optionId())) {
                    throw InternalCalls.contractViolation(Dependencies.ORDER_STOCKS, "묻지 않은 옵션이거나 수량이 빈 응답");
                }
                available.put(stock.optionId(), Math.max(0, stock.available()));
            }
        }
        return available;
    }
}
