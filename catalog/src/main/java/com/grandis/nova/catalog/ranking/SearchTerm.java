package com.grandis.nova.catalog.ranking;

import com.grandis.nova.catalog.listing.ProductListFilter;

import java.util.Locale;
import java.util.Objects;

/**
 * 인기 검색어로 셀 검색어 하나. 표기는 목록 검색과 같은 정규화(NFC · 앞뒤 공백 · 연속 공백 하나 · 50자 상한)를 거친 값이고, 합침 키는 거기서
 * 공백을 모두 빼고 소문자로 바꾼 값이다 — "아이폰 17" · "아이폰17" · "IPHONE" · "iPhone" 처럼 대소문자 · 공백만 다른 표기는 한 검색어로 센다.
 *
 * @param display 화면에 보일 표기(정규화한 검색어)
 * @param key     합침 키
 */
public record SearchTerm(String display, String key) {

    public SearchTerm {
        Objects.requireNonNull(display, "display");
        Objects.requireNonNull(key, "key");
    }

    /**
     * @return 비었으면 null
     * @throws com.grandis.nova.common.BusinessException VALIDATION_FAILED(q) — 정규화한 검색어가 50자를 넘음(목록 검색과 같은 상한)
     */
    public static SearchTerm of(String q) {
        String display = new ProductListFilter(q, null, null, null, null, null).q();
        if (display == null) {
            return null;
        }
        return new SearchTerm(display, display.replace(" ", "").toLowerCase(Locale.ROOT));
    }
}
