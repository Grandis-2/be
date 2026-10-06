package com.grandis.nova.catalog.api;

import com.grandis.nova.catalog.detail.AdminProductDetail;
import com.grandis.nova.catalog.detail.ProductDetailView;
import com.grandis.nova.catalog.registration.RegistrationStatusView;

/**
 * 관리자 상세 응답. 상품은 회원 상세와 같은 모양(visible 이 실제 값), tags 는 관리자만, 등록 상태는 등록 상태 조회와 같은 모양이다.
 *
 * @param registration 늘 실린다 — completed(판매 방식별 준비)는 관리자 목록의 registrationCompleted 와 같은 판정이다.
 *                     등록 API 이전에 들어온 상품은 idempotencyKey 가 null 이다
 */
public record AdminProductResponse(ProductDetailView product, String tags, RegistrationStatusView registration) {

    static AdminProductResponse from(AdminProductDetail detail) {
        return new AdminProductResponse(detail.product(), detail.tags(), new RegistrationStatusView(detail.product().productId(),
                detail.registrationKey(), detail.registrationCompleted()));
    }
}
