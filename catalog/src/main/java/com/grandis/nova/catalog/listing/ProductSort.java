package com.grandis.nova.catalog.listing;

/**
 * 회원 목록의 정렬. 셋뿐이다(2026-10-07 결정). 가격은 판매 중인 옵션의 최저가 — 목록 카드에 보이는 가격과 같은 값이라 화면의 순서와
 * 숫자가 어긋나지 않는다. 판매 중인 옵션이 없는 상품은 최저가가 없어 두 가격순 모두 맨 뒤다. 같은 값이면 최신순으로 잇는다.
 */
public enum ProductSort {

    /** 최신순(기본) — 등록 시각 내림차순. id 는 생성 순서를 보장하지 않아 동점 깨기로만 쓴다. */
    NEWEST(" ORDER BY p.created_at DESC, p.id DESC"),
    /** 낮은 가격순. */
    PRICE_ASC(" ORDER BY (min_price IS NULL), min_price ASC, p.created_at DESC, p.id DESC"),
    /** 높은 가격순. */
    PRICE_DESC(" ORDER BY (min_price IS NULL), min_price DESC, p.created_at DESC, p.id DESC");

    private final String orderBy;

    ProductSort(String orderBy) {
        this.orderBy = orderBy;
    }

    /** 목록 문장에 붙일 ORDER BY. min_price 는 SELECT 의 별칭이다. */
    String orderBy() {
        return orderBy;
    }
}
