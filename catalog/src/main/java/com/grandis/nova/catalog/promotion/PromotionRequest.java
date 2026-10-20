package com.grandis.nova.catalog.promotion;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 홍보 만들기 · 고치기 본문(POST · PUT /admin/promotions). 고치기도 모든 칸을 보낸다(관리자 수정 폼이 통째로 보낸다).
 *
 * @param startDate       고객 페이지에 보이기 시작하는 날(한국 날짜, 'YYYY-MM-DD')
 * @param endDate         보이는 마지막 날(포함). startDate 보다 앞이면 400
 * @param thumbnailUrl    목록 카드 · 상단 큰 사진. 업로드 API 가 준 주소만
 * @param detailImageUrls 상세 사진 — 넣은 순서대로 세로로 이어 붙는다. 업로드 API 가 준 주소만, 같은 주소는 한 번. 비우면 없음
 * @param productIds      묶을 상품(보이는 순서대로). 새로 묶는 상품은 회차가 취소되지 않은 사전예약 상품이어야 하고, 다른 홍보에 묶인 상품이면 409
 */
public record PromotionRequest(
        @NotBlank @Size(max = 100) String name,
        @NotNull LocalDate startDate,
        @NotNull LocalDate endDate,
        @NotBlank @Size(max = 1000) String thumbnailUrl,
        @Size(max = MAX_DETAIL_IMAGES) List<@NotBlank @Size(max = 1000) String> detailImageUrls,
        @NotNull @Size(min = 1, max = MAX_PRODUCTS) List<@NotNull UUID> productIds
) {

    public static final int MAX_DETAIL_IMAGES = 20;
    public static final int MAX_PRODUCTS = 20;

    public PromotionRequest {
        detailImageUrls = frozen(detailImageUrls);
        productIds = frozen(productIds);
    }

    /** null 원소를 남긴 채 얼린다 — 원소 검사(칸 경로)가 돌기 전에 NPE 로 죽지 않게(등록 요청과 같은 방식). */
    private static <T> List<T> frozen(List<T> list) {
        return list == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(list));
    }
}
