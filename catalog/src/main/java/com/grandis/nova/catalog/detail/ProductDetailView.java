package com.grandis.nova.catalog.detail;

import com.grandis.nova.catalog.listing.PreorderSaleStatus;
import com.grandis.nova.catalog.product.SaleMode;
import com.grandis.nova.catalog.product.SaleStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 상품 상세. 배송 차수는 싣지 않는다 — preorder 의 공개 API 를 프론트가 부른다(catalog 는 shipment_batches 를 읽지 않는다).
 *
 * @param visible        회원 상세에서는 늘 true(노출 규칙을 지난 상품만 나온다 — 아니면 누구에게나 404, 관리자 미리보기 없음).
 *                       관리자 목록 · 상세에서는 products.visible 칸 그대로이고 등록 완료(판매 방식별 준비)는 registration 이 따로 말한다
 * @param sellable       판매 중 옵션이 하나 이상 있다(목록과 같은 규칙). false 면 화면이 "판매 중지" 를 그린다
 * @param soldOut        일반 상품에서 가용 재고가 있는 판매 중 옵션이 없다(목록과 같은 규칙). 사전예약은 항상 false
 * @param campaign       사전예약 회차. 일반 상품은 null
 * @param variants       옵션 전체. 판매 중지 옵션도 상태 그대로 싣는다 — 화면이 선택을 막는다
 */
public record ProductDetailView(
        Long productId,
        Long categoryId,
        SaleMode saleMode,
        String title,
        String description,
        String imageUrl,
        SaleStatus status,
        boolean visible,
        BigDecimal basePrice,
        Warranty warranty,
        boolean sellable,
        boolean soldOut,
        Campaign campaign,
        List<OptionAxis> optionAxes,
        List<Variant> variants,
        Images images
) {

    public record Warranty(boolean offered, BigDecimal surcharge) {
    }

    public record Campaign(Instant opensAt, Instant closesAt, PreorderSaleStatus status) {
    }

    /** 옵션 축과 그 값. 화면의 선택기가 이 순서(축 position · 값 position)로 그린다. */
    public record OptionAxis(String key, String label, List<OptionValue> values) {
    }

    public record OptionValue(String value, String normalizedValue, BigDecimal surcharge) {
    }

    /**
     * @param selections        축 키 → 고른 값의 정규화값. 선택기가 조합을 찾는 키
     * @param availableQuantity 일반 상품의 가용 수량(총량 − 선점 − 판매, 재고 행이 없으면 0). 사전예약은 null(무제한 접수)
     */
    public record Variant(
            Long variantId,
            String sku,
            String title,
            BigDecimal price,
            Map<String, String> filterAttributes,
            Map<String, String> displayAttributes,
            Map<String, String> selections,
            SaleStatus status,
            Integer availableQuantity
    ) {
    }

    /**
     * @param gallery 색상 묶음별 상품 사진. 기본 묶음은 bundleKey "". 묶음 안은 position 순, 묶음은 사전순
     * @param detail  상세 콘텐츠 영역별 이미지(상세정보 · 제품사양 · 유의사항 …). 영역 안은 position 순
     */
    public record Images(List<ImageBundle> gallery, List<ImageBundle> detail) {
    }

    public record ImageBundle(String bundleKey, List<Image> items) {
    }

    public record Image(String url, int position, boolean primary) {
    }
}
