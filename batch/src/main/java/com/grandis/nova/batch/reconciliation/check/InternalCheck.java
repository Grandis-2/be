package com.grandis.nova.batch.reconciliation.check;

/**
 * 예약 한 건으로 볼 수 없는 be 내부 확인할 것(이벤트 · 시도 · 상품별 순번). 검사 트랜잭션 안에서 Mock 맞대기가 끝난 뒤 한 번
 * 불린다 — 같은 스냅샷을 보므로 SQL 로 어긋난 행을 찾는다. 업무 테이블에는 읽기만 한다(batch 계정이 막는다).
 * 어긋난 행이 예약 수만큼 나올 수 있는 조회는 {@link StreamingJdbc} 로 한 행씩 받는다.
 */
public interface InternalCheck {

    /**
     * @param scope 검사 범위와 경계 시각. 처리 중이 끝났어야 하는지는 멈춤 경계로 가린다
     */
    void inspect(RunScope scope, FindingSink findings);
}
