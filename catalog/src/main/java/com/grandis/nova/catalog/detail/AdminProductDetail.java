package com.grandis.nova.catalog.detail;

import com.grandis.nova.catalog.product.RegistrationSnapshot;

/**
 * 관리자 상세 = 회원 상세와 같은 상품 모양 + 관리자만 보는 tags + 등록 기록. 셋은 한 트랜잭션(REPEATABLE READ)의 한 스냅샷이고
 * 상품과 등록 기록은 같은 문장에서 왔다.
 *
 * @param registration 등록 기록. 등록 API 이전에 들어온 상품은 null
 */
public record AdminProductDetail(ProductDetailView product, String tags, RegistrationSnapshot registration) {
}
