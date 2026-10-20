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

    /**
     * 검색어 최대 길이(코드 포인트, 정규화 뒤). 회원 검색창의 maxLength(50, UTF-16 단위)와 같아 보통 입력은 걸리지 않는다 — NFC 가 길이를
     * 늘리는 드문 문자(U+0958 은 2, U+FB2C 는 3 코드 포인트가 된다)는 50 안이어도 넘을 수 있다(실측). 관리자 검색창에도 같은 50자를 두기로
     * 했다(2026-10-18 결정, 프론트 반영 전에는 51자부터 400).
     */
    public static final int MAX_QUERY_LENGTH = 50;
    /**
     * brand · color · storage 를 한 요청에 보낼 수 있는 최대 개수 — IN 목록의 크기를 묶는다. 여러 상품에 걸친 OR 이라 카탈로그 전체의
     * 서로 다른 색상 · 용량은 20개를 넘을 수 있다. 프론트(2026-10-18 develop)는 color · storage 필터를 보내지 않는다.
     */
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
     * 보낸 개수 그대로 센다(빈 값 · 중복을 거르기 전). 이름을 한 번만 보내면 쉼표로 이은 값(color=a,b)을 스프링이 나눠 하나씩 센다 —
     * 같은 이름을 여러 번 보내면 나누지 않고 그 개수로 센다(실측). IN 목록에 들어가는 값도 같은 방식으로 정해져 상한을 돌아갈 수는 없다.
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
