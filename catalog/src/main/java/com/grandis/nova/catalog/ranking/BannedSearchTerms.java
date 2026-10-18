package com.grandis.nova.catalog.ranking;

import java.util.List;

/**
 * 인기 검색어에서 빼는 말 — 코드 목록만 둔다(2026-10-17 결정, 관리 화면 없음). 합침 키(공백 없음 · 소문자)에 이 말이 들어 있으면 세지 않는다.
 * 고치면 다음 배포부터 새 검색만 거른다 — 이미 센 검색어는 24시간 창에서 빠질 때까지 남는다.
 */
final class BannedSearchTerms {

    static final List<String> WORDS = List.of("시발", "씨발", "씨바", "ㅅㅂ", "병신", "ㅂㅅ", "개새끼", "좆", "존나", "fuck", "shit");

    private BannedSearchTerms() {
    }

    static boolean contains(SearchTerm term) {
        return WORDS.stream().anyMatch(term.key()::contains);
    }
}
