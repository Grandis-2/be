package com.grandis.nova.order.stock.domain.repository;

import com.grandis.nova.order.stock.domain.model.StockReconcileRun;

import java.util.Optional;

/** 끝난 대조의 기록(stock_reconcile_runs). */
public interface StockReconcileRuns {

    void save(StockReconcileRun run);

    /** 가장 최근에 끝난 대조. */
    Optional<StockReconcileRun> findLatest();

    /** 가장 최근에 끝난 대조의 끝난 시각 · 어긋난 옵션 수만 — 지표가 주기마다 읽는다(옵션 목록은 읽지 않는다). */
    Optional<StockReconcileRun.Summary> findLatestSummary();
}
