package com.grandis.nova.catalog.listing;

import com.grandis.nova.catalog.option.ProductOptionValue;
import com.grandis.nova.catalog.product.SaleMode;
import com.grandis.nova.catalog.product.SaleStatus;

/**
 * 관리자 목록 조건. 노출 규칙이 없다 — 비공개 · 미완료 · 판매 중지 · 오래된 마감도 전부 나온다(관리 화면은 그것들을 보러 온다).
 *
 * @param q      상품명 · tags 부분 일치(대소문자 무시). 회원 목록과 같은 정규화(NFC · 트림 · 공백 접기). 비면 null
 * @param status 상품의 판매 상태(ACTIVE · PAUSED)
 */
public record AdminProductListFilter(String q, SaleMode saleMode, SaleStatus status) {

    public AdminProductListFilter {
        q = q == null || q.isBlank() ? null : ProductOptionValue.normalize(q);
    }

    public static AdminProductListFilter none() {
        return new AdminProductListFilter(null, null, null);
    }
}
