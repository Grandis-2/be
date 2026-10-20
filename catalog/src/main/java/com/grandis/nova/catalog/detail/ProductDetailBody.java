package com.grandis.nova.catalog.detail;

/**
 * 읽기 캐시에 두는 상품 상세 본문 — 화면 칸(실시간 칸은 비운 채)과 일반 상품의 판매 준비(products.stock_ready_at).
 * 판매 준비는 catalog 의 칸이고 order 의 판매 준비 이벤트를 받아 채울 때 상품 세대를 올리므로(InStockProductReadyHandler) 본문과 같이 캐시해도 된다.
 *
 * @param stockReady 일반 상품이 판매 준비됐다. 사전예약은 늘 false — 사전예약의 준비는 요청마다 preorder 회차로 본다
 */
public record ProductDetailBody(ProductDetailView view, boolean stockReady) {
}
