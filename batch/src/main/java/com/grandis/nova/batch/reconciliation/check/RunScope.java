package com.grandis.nova.batch.reconciliation.check;

import java.time.Instant;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * 검사 한 번의 조건.
 *
 * @param productId   검사 범위. null 이면 전체
 * @param cutoff      판정 기준 시각. 이 뒤에 바뀐 키는 판정하지 않는다
 * @param maxFindings 불일치 상한. 넘으면 그 실행은 판정 불가다 — 그만큼 어긋났으면 개별 결과보다 설정 오류일 가능성이 크다
 * @param beHasKey    상품 구분 없이 그 외부 키의 be 예약이 있는가. 상품 범위에서 다른 상품의 예약 키를 가린다
 */
public record RunScope(UUID productId, Instant cutoff, int maxFindings, Predicate<String> beHasKey) {

    public RunScope {
        if (cutoff == null || beHasKey == null || maxFindings < 1) {
            throw new IllegalArgumentException("기준 시각 · 키 확인이 있고 상한은 1 이상이어야 한다");
        }
    }
}
