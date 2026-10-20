package com.grandis.nova.catalog.detail;

import com.grandis.nova.catalog.listing.PreorderSaleStatus;
import com.grandis.nova.catalog.product.Brand;
import com.grandis.nova.catalog.product.SaleMode;
import com.grandis.nova.catalog.product.SaleStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 상품 상세. 배송 차수는 싣지 않는다 — preorder 의 공개 API 를 프론트가 부른다(catalog 는 shipment_batches 를 읽지 않는다).
 *
 * @param visible        회원 상세에서는 늘 true(노출 규칙을 지난 상품만 나온다 — 아니면 누구에게나 404, 관리자 미리보기 없음).
 *                       관리자 목록 · 상세에서는 products.visible 칸 그대로이고 등록 완료(판매 방식별 준비)는 registration 이 따로 말한다
 * @param sellable       판매 중 옵션이 하나 이상 있다(목록과 같은 규칙). false 면 화면이 "판매 중지" 를 그린다
 * @param soldOut        일반 상품에서 가용 재고가 있는 판매 중 옵션이 없다(목록과 같은 규칙). 사전예약은 항상 false
 * @param basePrice      기본가(정가 기준). 옵션 정가 = 기본가 + 고른 값의 추가금
 * @param discountRate   할인율(정수 %, 0~99). 모든 옵션에 같이 걸린다. 보증 추가금에는 걸지 않는다
 * @param campaign       사전예약 회차. 일반 상품은 null
 * @param variants       옵션 전체. 판매 중지 옵션도 상태 그대로 싣는다 — 화면이 선택을 막는다
 */
public record ProductDetailView(
        UUID productId,
        UUID categoryId,
        SaleMode saleMode,
        String title,
        Brand brand,
        String modelName,
        String description,
        String imageUrl,
        SaleStatus status,
        boolean visible,
        BigDecimal basePrice,
        int discountRate,
        Warranty warranty,
        boolean sellable,
        boolean soldOut,
        Campaign campaign,
        List<OptionAxis> optionAxes,
        List<Variant> variants,
        Images images
) {

    /** 실시간 칸(회차 · 옵션의 가용 수량 · 품절)만 바꾼 사본 — 캐시해 둔 본문에 요청마다 채운다(ProductDetailService#withLiveParts). */
    public ProductDetailView withLive(Campaign liveCampaign, List<Variant> liveVariants, boolean liveSoldOut) {
        return new ProductDetailView(productId, categoryId, saleMode, title, brand, modelName, description, imageUrl, status, visible,
                basePrice, discountRate, warranty, sellable, liveSoldOut, liveCampaign, optionAxes, liveVariants, images);
    }

    public record Warranty(boolean offered, BigDecimal surcharge) {
    }

    public record Campaign(Instant opensAt, Instant closesAt, PreorderSaleStatus status) {
    }

    /** 옵션 축과 그 값. 화면의 선택기가 이 순서(관리자가 넣은 축 · 값 순)로 그린다. */
    public record OptionAxis(String key, String label, List<OptionValue> values) {
    }

    /**
     * @param valueId 값의 고정 id(문자열). 이름을 고쳐도 그대로다 — 관리자 값 수정 경로(…/option-values/{valueId})가 이것을 받는다
     * @param hex     색상 스와치(#RRGGBB). 색상 축이 아니거나 등록하지 않았으면 null
     */
    public record OptionValue(String valueId, String value, String normalizedValue, String hex, BigDecimal surcharge) {
    }

    /**
     * @param price             회원이 내는 값 — 정가에 할인율을 걸어 100원 단위로 버린 할인가. 할인율이 0 이면 정가와 같다
     * @param listPrice         정가(기본가 + 고른 값의 추가금). 화면의 취소선 가격
     * @param selections        축 키 → 고른 값의 정규화값. 선택기가 조합을 찾는 키
     * @param availableQuantity 일반 상품의 남은 수량(order 재고 API 의 답, 재고 행이 없으면 0). null 은 판매 방식으로 뜻이 갈린다 — 사전예약은 무제한
     *                          접수, 일반 상품은 수량을 모름(order 가 답하지 않음 — 품절도 false). 주문 · 장바구니의 수량 한도는 order 가 따로 지킨다
     */
    public record Variant(
            UUID variantId,
            String sku,
            String title,
            BigDecimal price,
            BigDecimal listPrice,
            Map<String, String> filterAttributes,
            Map<String, String> selections,
            SaleStatus status,
            Integer availableQuantity
    ) {

        public Variant withAvailableQuantity(Integer quantity) {
            return new Variant(variantId, sku, title, price, listPrice, filterAttributes, selections, status, quantity);
        }
    }

    /**
     * @param gallery 색상 묶음별 상품 사진(bundleKey = 색상의 정규화값). 색상 축이 없으면 기본 묶음 하나(bundleKey ""). 묶음은 색상 값 순, 묶음 안은 넣은 순
     * @param detail  상세 콘텐츠 영역별 이미지(상세정보 · 제품사양 · 유의사항 …). 영역은 넣은 순, 영역 안은 넣은 순
     */
    public record Images(List<ImageBundle> gallery, List<ImageBundle> detail) {
    }

    public record ImageBundle(String bundleKey, List<Image> items) {
    }

    public record Image(String url, int position, boolean primary) {
    }
}
