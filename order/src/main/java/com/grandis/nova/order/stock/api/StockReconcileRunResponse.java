package com.grandis.nova.order.stock.api;

import com.grandis.nova.order.stock.admin.StockReconcileQueryService.LatestRun;
import com.grandis.nova.order.stock.domain.model.StockBalance;
import com.grandis.nova.order.stock.domain.model.StockReconcileRun;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 마지막 재고 장부 대조. options 는 어긋난 옵션, option_id 오름차순 — 대조 때 본 값이다.
 *
 * @param driftOptions 어긋난 옵션 수(전부)
 * @param truncated    어긋난 옵션이 많아 options 에 앞의 일부만 실었다(StockReconcileRun.DETAIL_LIMIT)
 */
public record StockReconcileRunResponse(Instant startedAt, Instant finishedAt, int driftOptions, boolean truncated, List<Option> options) {

    static StockReconcileRunResponse from(LatestRun latest) {
        StockReconcileRun run = latest.run();
        return new StockReconcileRunResponse(run.startedAt(), run.finishedAt(), run.driftOptions(), run.truncated(),
                run.drifts().stream().map(drift -> Option.of(drift, latest.correctedAt().get(drift.optionId()))).toList());
    }

    /**
     * @param productId   옵션의 상품 — 보정 경로에 쓴다. catalog 에 옵션 행이 없으면 null
     * @param registered  재고 행이 있었는가
     * @param correctedAt 대조가 끝난 뒤 마지막 보정 시각. 보정하지 않았으면 null
     */
    public record Option(UUID optionId, UUID productId, boolean registered, int stockTotal, long expectedReserved, int stockReserved,
                         long expectedSold, int stockSold, Instant correctedAt) {

        static Option of(StockBalance drift, Instant correctedAt) {
            return new Option(drift.optionId(), drift.productId(), drift.registered(), drift.total(), drift.expectedReserved(),
                    drift.actualReserved(), drift.expectedSold(), drift.actualSold(), correctedAt);
        }
    }
}
