package com.grandis.nova.preorder.event;

/**
 * catalog 가 사전예약 상품을 바꿨다. 상품 id 는 봉투의 aggregateId 다.
 * 공개 여부 칸은 둘 다 있을 때만 쓴다 — 이 칸을 모르는 catalog 는 비운 채 보낸다.
 */
record PreorderProductChanged(Boolean visible, Long visibilityVersion) {

    boolean hasVisibility() {
        return visible != null && visibilityVersion != null;
    }
}
