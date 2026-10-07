package com.grandis.nova.catalog.listing;

import com.grandis.nova.catalog.option.OptionText;
import com.grandis.nova.catalog.product.SaleMode;

import java.util.List;

/**
 * 목록 · 검색 조건. 색상 · 용량은 축 안에서 OR, 축 사이는 AND 이고 **같은 옵션**이 두 조건을 함께 만족해야 한다
 * (블랙 · 화이트 + 256GB → 블랙 256GB 또는 화이트 256GB 인 실제 옵션이 있어야 한다).
 * 값은 저장과 같은 메서드({@link OptionText#normalizeFor})로 정규화한다 — 용량은 공백 제거 · 대문자, 색상은 NFC · 트림 · 공백 접기.
 * q 도 NFC · 트림 · 공백 접기를 거친다. 콜레이션은 `=` 에서 NFD 를 같게 보지만 LIKE 는 아니다(실측) — 맥 한글 입력기는 NFD 를 흔히 보낸다.
 *
 * @param q          상품명 · 관리자 tags 부분 일치(대소문자 무시). 비면 null
 * @param categoryId 상위면 하위에 배정된 상품까지 포함
 */
public record ProductListFilter(String q, Long categoryId, SaleMode saleMode, List<String> colors, List<String> storages) {

    public ProductListFilter {
        q = q == null || q.isBlank() ? null : OptionText.normalize(q);
        colors = normalize(OptionText.COLOR, colors);
        storages = normalize(OptionText.STORAGE, storages);
    }

    public static ProductListFilter none() {
        return new ProductListFilter(null, null, null, List.of(), List.of());
    }

    private static List<String> normalize(String axisKey, List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> OptionText.normalizeFor(axisKey, value))
                .distinct()
                .toList();
    }
}
