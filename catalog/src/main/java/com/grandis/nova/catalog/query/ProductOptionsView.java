package com.grandis.nova.catalog.query;

import com.grandis.nova.catalog.product.SaleMode;
import com.grandis.nova.catalog.product.SaleStatus;

import java.math.BigDecimal;
import java.util.List;

/**
 * 상품과 옵션 전체 — 내부 조회 API(GET /internal/products/{id}/options)의 응답. 계약: contracts/preorder-internal.md.
 *
 * 비공개 · 미완료 상품도 그대로 담는다. 숨길지는 목적별로 호출자가 판정한다 — 계약상 앞으로 회차 설정은 saleMode 만 보고,
 * 접수는 status · visible · registrationCompleted 까지 보게 된다(지금 preorder 는 두 곳 모두 saleMode·status 만 본다).
 * 여기서 먼저 숨기면 관리자 등록 흐름이 자기 상품을 못 본다. visible 로 완료를 추론하지 않는다 — 두 칸을 모두 본다.
 *
 * @param registrationCompleted 판매 방식별 준비 — 사전예약은 preorder 회차 행, 일반은 order 재고 행이 있다(등록 이벤트가 처리됐다)
 */
public record ProductOptionsView(
        Long productId,
        String title,
        SaleMode saleMode,
        SaleStatus status,
        boolean visible,
        boolean registrationCompleted,
        List<Option> options
) {

    public ProductOptionsView {
        options = options == null ? List.of() : List.copyOf(options);
    }

    /** @param price 최종가(정수 원). 스냅샷 원본 */
    public record Option(Long optionId, String sku, String title, BigDecimal price, SaleStatus status) {
    }
}
