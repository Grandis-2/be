package com.grandis.nova.catalog.detail;

/**
 * 관리자 상세 = 회원 상세와 같은 상품 모양 + 관리자만 보는 tags + 등록 상태. DB 칸은 한 트랜잭션에서 읽는다 — 관리자 상세 조회는
 * REPEATABLE READ 한 스냅샷, 쓰기 응답은 그 쓰기 트랜잭션. 일반 상품의 남은 수량은 그 뒤 order 가 답한 값이다.
 *
 * @param registrationKey       등록 때 받은 멱등 키. 등록 API 밖에서 들어온 상품은 null
 * @param registrationCompleted 판매 방식별 준비 — 사전예약은 preorder 회차 행, 일반은 order 의 판매 준비 이벤트를 받았다(products.stock_ready_at)
 */
public record AdminProductDetail(ProductDetailView product, String tags, String registrationKey, boolean registrationCompleted) {
}
