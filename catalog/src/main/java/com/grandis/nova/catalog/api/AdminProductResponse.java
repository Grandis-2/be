package com.grandis.nova.catalog.api;

import com.grandis.nova.catalog.detail.AdminProductDetail;
import com.grandis.nova.catalog.detail.ProductDetailView;
import com.grandis.nova.catalog.registration.RegistrationStatusView;

/**
 * 관리자 상세 응답. 상품은 회원 상세와 같은 모양(visible 이 실제 값), tags 는 관리자만, 등록 기록은 등록 상태 조회와 같은 모양이다.
 *
 * @param registration 등록 API 이전에 들어온 상품은 null
 */
public record AdminProductResponse(ProductDetailView product, String tags, RegistrationStatusView registration) {

    static AdminProductResponse from(AdminProductDetail detail) {
        RegistrationStatusView registration = detail.registration() == null ? null
                : RegistrationStatusView.from(detail.product().productId(), detail.registration());
        return new AdminProductResponse(detail.product(), detail.tags(), registration);
    }
}
