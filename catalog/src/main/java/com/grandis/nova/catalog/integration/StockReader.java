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
 * 옵션별 남은 수량을 order 에 묻는다({@link StockClient}). 한 번에 {@value #BATCH}개씩 차례로 부른다(계약 상한) — 묶음마다의 응답 시간이 그대로
 * 더해진다 — 최악은 묶음 수 × 읽기 타임아웃(1s). 목록 · 랭킹은 읽기 캐시 로더 안에서 부르므로, 이게 캐시 대기(5s)를 넘으면 같은 키를 기다리던
 * 요청은 503 이다(알려진 한계 — 지금 쪽 크기 · 옵션 수면 1~2번). DB 트랜잭션 밖에서 부른다 — 응답을 기다리는 동안 연결 · 행 잠금을 붙잡지 않게(시험용 대역이 트랜잭션 안의 호출을 센다).
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
     *                                                   읽을 수 없는 응답 · items 없음 · 묻지 않은 옵션 · 겹친 옵션 · 비었거나 음수인 수량, ERROR 로그를 남긴다 — {@link InternalCalls})
     */
    public Map<UUID, Integer> available(Collection<UUID> optionIds) {
        Map<UUID, Integer> available = new HashMap<>();
        List<UUID> ids = new ArrayList<>(new LinkedHashSet<>(optionIds));
        for (int from = 0; from < ids.size(); from += BATCH) {
            List<UUID> batch = ids.subList(from, Math.min(ids.size(), from + BATCH));
            StockClient.Stocks stocks = InternalCalls.call(Dependencies.ORDER_STOCKS, () -> client.getStocks(batch));
            if (stocks.items() == null) {
                // 빈 목록과 다르다 — 지어낸 0 으로 품절을 보이지 않게 연동 오류로 본다
                throw InternalCalls.contractViolation(Dependencies.ORDER_STOCKS, "items 가 없는 응답");
            }
            for (StockClient.Stock stock : stocks.items()) {
                // order 는 옵션마다 한 번, 0 이상으로 답한다(계약) — 어긋나면 수량을 지어내지 않고 연동 오류로 본다
                if (stock.optionId() == null || stock.available() == null || stock.available() < 0 || !batch.contains(stock.optionId())
                        || available.put(stock.optionId(), stock.available()) != null) {
                    throw InternalCalls.contractViolation(Dependencies.ORDER_STOCKS, "묻지 않은 옵션 · 겹친 옵션 · 비었거나 음수인 수량");
                }
            }
        }
        return available;
    }
}
