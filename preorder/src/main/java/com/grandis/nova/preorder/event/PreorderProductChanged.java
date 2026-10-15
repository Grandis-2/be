package com.grandis.nova.preorder.event;

import com.grandis.nova.preorder.campaign.ProductListing;
import com.grandis.nova.preorder.campaign.ProductStatus;

/**
 * catalog 가 사전예약 상품을 바꿨다. 상품 id 는 봉투의 aggregateId 다.
 * 상품명 · 판매 상태 · 공개 여부 · 번호는 넷이 다 있을 때만 쓴다 — 이 칸을 모르는 catalog 는 빈 payload 를 보낸다.
 */
record PreorderProductChanged(String title, String status, Boolean visible, Long listingVersion) {

    /**
     * @return 회차 사본에 쓸 값. 칸이 빠졌으면 null(캐시만 비운다)
     * @throws IllegalArgumentException 모르는 판매 상태 — 계약 위반이라 재시도 뒤 DLQ 로 간다
     */
    ProductListing toListing() {
        if (title == null || status == null || visible == null || listingVersion == null) {
            return null;
        }
        return new ProductListing(title, ProductStatus.valueOf(status), visible, listingVersion);
    }
}
