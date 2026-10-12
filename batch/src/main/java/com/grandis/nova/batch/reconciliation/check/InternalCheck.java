package com.grandis.nova.batch.reconciliation.check;

import java.time.Instant;
import java.util.UUID;

/**
 * 예약 한 건으로 볼 수 없는 be 내부 확인할 것(이벤트 · 시도 · 상품별 순번). 검사 트랜잭션 안에서 Mock 맞대기가 끝난 뒤 한 번
 * 불린다 — 같은 스냅샷을 보므로 SQL 로 어긋난 행을 찾는다. 업무 테이블에는 읽기만 한다(batch 계정이 막는다).
 */
public interface InternalCheck {

    /**
     * @param productId 검사 범위. null 이면 전체
     * @param cutoff    판정 기준 시각. 이 뒤에 바뀐 행은 판정하지 않는다
     */
    void inspect(UUID productId, Instant cutoff, FindingSink findings);
}
