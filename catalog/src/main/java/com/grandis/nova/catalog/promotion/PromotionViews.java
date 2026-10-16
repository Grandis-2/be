package com.grandis.nova.catalog.promotion;

import com.grandis.nova.catalog.listing.ProductListItem;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** 고객 사전예약 페이지의 홍보 응답. */
public final class PromotionViews {

    private PromotionViews() {
    }

    /**
     * 목록 카드.
     *
     * @param opensAt  묶인(공개 · 판매 중) 사전예약 상품 중 가장 이른 오픈. 사전예약 상품이 없으면(전부 일반 전환 · 비공개 · 판매 중지) null — 화면은 진행 중 · 오픈 예정 · 마감을 이것으로 가른다
     * @param closesAt 가장 늦은 마감
     */
    public record Summary(UUID promotionId, String name, String thumbnailUrl, LocalDate startDate, LocalDate endDate,
                          Instant opensAt, Instant closesAt) {
    }

    /**
     * 상세 — 목록 카드 + 상세 사진 + 묶인 상품(상품 목록과 같은 칸 — 할인가 · 정가 · 할인율 · 접수 단계 · 회차 시각). 상품은 공개 · 판매 중 · 판매 준비가
     * 끝난 것만(판매 중지 · 회차 취소는 뺀다), 마감 뒤 · 일반 전환 뒤에도 싣는다(화면이 "일반 구매" 로 보인다).
     */
    public record Detail(UUID promotionId, String name, String thumbnailUrl, List<String> detailImageUrls, LocalDate startDate,
                         LocalDate endDate, Instant opensAt, Instant closesAt, List<ProductListItem> products) {
    }
}
