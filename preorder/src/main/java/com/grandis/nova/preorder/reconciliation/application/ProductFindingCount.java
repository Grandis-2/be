package com.grandis.nova.preorder.reconciliation.application;

import java.util.UUID;

/**
 * 실행의 상품 하나의 불일치 건수.
 *
 * @param productId   상품을 알 수 없는 건(Mock 에만 있는 취소 표식 등 · 상품 칸이 생기기 전 결과)은 null
 * @param productName 회차 사본의 상품 이름. 모르면 null
 */
public record ProductFindingCount(UUID productId, String productName, long count) {
}
