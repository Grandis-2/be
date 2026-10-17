package com.grandis.nova.catalog.listing;

import com.grandis.nova.catalog.web.ValidationFailures;
import com.grandis.nova.common.BusinessException;

import java.util.List;

/**
 * 목록 · 검색 조건의 크기 상한. 회원 목록 · 관리자 목록 · 증정품 후보가 같은 규칙을 쓴다.
 * q 는 상품명 · tags · 모델명 · 브랜드 네 칸에 앞뒤 와일드카드 LIKE 로 걸리고, 반복 값은 IN 목록이 된다 — 상한이 없으면
 * API 를 직접 불러 아주 큰 조건을 만들 수 있다.
 */
public final class ListQueryLimits {

    /** 검색어 최대 길이(코드 포인트, 정규화 뒤). 프론트 입력창(50자)과 같다. */
    public static final int MAX_QUERY_LENGTH = 50;
    /** brand · color · storage 를 한 요청에 보낼 수 있는 최대 개수. 등록 때 한 축의 값이 20개까지라 그보다 많을 일이 없다. */
    public static final int MAX_VALUES = 20;

    private ListQueryLimits() {
    }

    /** @throws BusinessException VALIDATION_FAILED(q) — 정규화한 검색어가 50자를 넘음 */
    static String query(String normalized) {
        if (normalized != null && normalized.codePointCount(0, normalized.length()) > MAX_QUERY_LENGTH) {
            throw ValidationFailures.of("q", "%d자 이하여야 합니다.".formatted(MAX_QUERY_LENGTH));
        }
        return normalized;
    }

    /**
     * 보낸 개수 그대로 센다(빈 값 · 중복을 거르기 전). 쉼표로 이어 보낸 값(color=a,b)도 스프링이 나눈 뒤라 하나씩 센다.
     *
     * @throws BusinessException VALIDATION_FAILED(field) — 20개를 넘음
     */
    static <T> List<T> values(String field, List<T> values) {
        if (values != null && values.size() > MAX_VALUES) {
            throw ValidationFailures.of(field, "%d개 이하여야 합니다.".formatted(MAX_VALUES));
        }
        return values;
    }
}
