package com.grandis.nova.batch.reconciliation.check;

import java.time.Instant;
import java.util.UUID;

/**
 * 검사 한 번의 조건.
 *
 * @param productId   검사 범위. null 이면 전체
 * @param cutoff      판정 기준 시각. 이 뒤에 바뀐 키는 판정하지 않는다
 * @param maxFindings 불일치 상한. 넘으면 그 실행은 판정 불가다 — 그만큼 어긋났으면 개별 결과보다 설정 오류일 가능성이 크다
 */
public record RunScope(UUID productId, Instant cutoff, int maxFindings) {

    public RunScope {
        if (cutoff == null || maxFindings < 1) {
            throw new IllegalArgumentException("기준 시각이 있고 상한은 1 이상이어야 한다");
        }
    }
}
