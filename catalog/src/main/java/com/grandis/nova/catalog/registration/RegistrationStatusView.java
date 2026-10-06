package com.grandis.nova.catalog.registration;

/**
 * 등록 상태. GET /admin/products/registrations/{key} · 등록 응답 · 관리자 상세에 실린다.
 *
 * @param idempotencyKey 등록 기록의 멱등 키. 관리자 상세에서 등록 API 이전에 들어온 상품은 null 이다
 * @param completed      판매 방식별 준비 — 사전예약은 preorder 회차 행, 일반은 order 재고 행이 있다. 등록 이벤트를 받은 서비스가 처리하면 true 가 된다
 */
public record RegistrationStatusView(
        Long productId,
        String idempotencyKey,
        boolean completed
) {

    static RegistrationStatusView of(ProductRegistration registration, boolean completed) {
        return new RegistrationStatusView(registration.getProductId(), registration.getIdempotencyKey(), completed);
    }
}
