package com.grandis.nova.order.stock.admin;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.order.OrderErrorCode;
import com.grandis.nova.order.stock.domain.model.StockBalance;
import com.grandis.nova.order.stock.domain.model.StockReconcileRun;
import com.grandis.nova.order.stock.domain.repository.StockAdjustments;
import com.grandis.nova.order.stock.domain.repository.StockReconcileRuns;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 관리자 재고 장부 대조 결과 — 마지막으로 끝난 대조와 어긋난 옵션 목록. 운영자는 이 목록으로 옵션마다 미리보기 · 보정한다
 * (어긋남은 경보로 보내지 않는다). 목록은 대조 때 본 값이라, 그 뒤 보정한 옵션에는 마지막 보정 시각을 붙인다.
 */
@Service
public class StockReconcileQueryService {

    private final StockReconcileRuns runs;
    private final StockAdjustments adjustments;
    private final TransactionTemplate readTransaction;

    public StockReconcileQueryService(StockReconcileRuns runs, StockAdjustments adjustments, PlatformTransactionManager transactionManager) {
        this.runs = runs;
        this.adjustments = adjustments;
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
    }

    /** @throws BusinessException STOCK_RECONCILE_RUN_NOT_FOUND(아직 끝난 대조가 없다) */
    public LatestRun latest() {
        return readTransaction.execute(status -> {
            StockReconcileRun run = runs.findLatest()
                    .orElseThrow(() -> new BusinessException(OrderErrorCode.STOCK_RECONCILE_RUN_NOT_FOUND));
            return new LatestRun(run, adjustments.lastCorrectedSince(run.drifts().stream().map(StockBalance::optionId).toList(),
                    run.finishedAt()));
        });
    }

    /** @param correctedAt 옵션 id → 대조가 끝난 뒤 마지막 보정 시각. 보정하지 않은 옵션은 없다 */
    public record LatestRun(StockReconcileRun run, Map<UUID, Instant> correctedAt) {
    }
}
