package com.grandis.nova.preorder.campaign;

/**
 * 회차 행에 두는 catalog 상품 값의 사본 — 상품명 · 판매 상태 · 공개 여부와 그 변경 번호(모듈 공개 API 입력).
 * 오픈 알림이 문구 · 대상 판정에 쓴다. 번호가 지금보다 클 때만 셋을 함께 덮는다(contracts/catalog-events.md).
 *
 * @param title   상품명. null 이면 catalog 가 싣지 않았다(이 칸을 모르는 catalog)
 * @param status  판매 상태. null 이면 위와 같다
 * @param version catalog 의 products.listing_version. 0 = catalog 값을 받기 전 — catalog 의 첫 번호(1)부터 이긴다
 */
public record ProductListing(String title, ProductStatus status, boolean visible, long version) {

    /** catalog 이벤트가 이기도록 번호 0 으로 둔 값. 관리자가 먼저 회차를 만들 때 쓴다. */
    public static ProductListing beforeCatalog(String title, ProductStatus status, boolean visible) {
        return new ProductListing(title, status, visible, 0);
    }
}
