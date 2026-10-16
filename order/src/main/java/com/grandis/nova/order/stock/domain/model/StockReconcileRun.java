package com.grandis.nova.order.stock.domain.model;

import java.time.Instant;
import java.util.List;

/**
 * 끝난 재고 장부 대조 한 번. 실패한 대조는 남기지 않는다.
 *
 * @param driftOptions 어긋난 옵션 수(전부)
 * @param drifts       어긋난 옵션, option_id 오름차순. 앞 {@link #DETAIL_LIMIT}개까지만 싣는다
 * @param truncated    어긋난 옵션이 {@link #DETAIL_LIMIT}개를 넘어 뒤를 잘랐다
 */
public record StockReconcileRun(Instant startedAt, Instant finishedAt, int driftOptions, List<StockBalance> drifts,
                                boolean truncated) {

    /** 기록 · 관리자 조회에 싣는 옵션 수의 상한. 처음 켤 때 과거 데이터로 어긋남이 많아도 기록 한 행 · 응답 하나가 끝없이 커지지 않게. */
    public static final int DETAIL_LIMIT = 1000;

    public StockReconcileRun {
        drifts = List.copyOf(drifts);
    }

    public static StockReconcileRun of(Instant startedAt, Instant finishedAt, List<StockBalance> drifts) {
        boolean truncated = drifts.size() > DETAIL_LIMIT;
        return new StockReconcileRun(startedAt, finishedAt, drifts.size(), truncated ? drifts.subList(0, DETAIL_LIMIT) : drifts, truncated);
    }

    /** 경보 · 대시보드가 보는 값만. */
    public record Summary(Instant finishedAt, int driftOptions) {
    }
}
