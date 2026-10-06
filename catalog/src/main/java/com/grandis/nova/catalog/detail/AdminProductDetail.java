package com.grandis.nova.catalog.detail;

/**
 * 관리자 상세 = 회원 상세와 같은 상품 모양 + 관리자만 보는 tags + 등록 상태. 한 트랜잭션(REPEATABLE READ)의 한 스냅샷이다.
 *
 * @param registrationKey       등록 기록의 멱등 키. 등록 API 이전에 들어온 상품은 null
 * @param registrationCompleted 판매 방식별 준비 — 사전예약은 preorder 회차 행, 일반은 order 재고 행이 있다
 */
public record AdminProductDetail(ProductDetailView product, String tags, String registrationKey, boolean registrationCompleted) {
}
