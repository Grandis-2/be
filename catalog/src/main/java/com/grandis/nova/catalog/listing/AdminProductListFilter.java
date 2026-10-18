package com.grandis.nova.catalog.listing;

import com.grandis.nova.catalog.option.OptionText;
import com.grandis.nova.catalog.product.Brand;
import com.grandis.nova.catalog.product.SaleMode;
import com.grandis.nova.catalog.product.SaleStatus;

import java.util.List;
import java.util.Objects;

/**
 * 관리자 목록 조건. 노출 규칙이 없다 — 비공개 · 미완료 · 판매 중지 · 오래된 마감도 전부 나온다(관리 화면은 그것들을 보러 온다).
 *
 * @param q      상품명 · tags · 모델명 · 브랜드 코드 부분 일치(대소문자 무시). 회원 목록과 같은 정규화(NFC · 트림 · 공백 접기)와
 *               크기 상한({@link ListQueryLimits} — 정규화한 뒤 50자). 비면 null
 * @param status 상품의 판매 상태(ACTIVE · PAUSED)
 * @param brands 브랜드 OR. 비면 거르지 않는다. 보낸 개수로 20개까지
 */
public record AdminProductListFilter(String q, SaleMode saleMode, SaleStatus status, List<Brand> brands) {

    public AdminProductListFilter {
        q = ListQueryLimits.query(q == null || q.isBlank() ? null : OptionText.normalize(q));
        brands = brands == null ? List.of() : ListQueryLimits.values("brand", brands).stream().filter(Objects::nonNull).distinct().toList();
    }

    public static AdminProductListFilter none() {
        return new AdminProductListFilter(null, null, null, List.of());
    }
}
