package com.grandis.nova.batch.reconciliation.check;

/**
 * 확인할 것 코드(reconciliation_findings.check_code). 화면의 유형 · 문구 · 조치 버튼은 preorder 가 이 이름으로 정한다.
 * 결과 테이블에 CHECK 가 없어 코드를 더해도 마이그레이션이 필요 없다.
 *
 * 만료 누락(EXPIRY_MISSED)은 만료 스캔이 생길 때까지 검사하지 않는다(10-12). 재고 불일치(STOCK_MISMATCH) · 결제 · 예약
 * 불일치(ORDER_PAYMENT_MISMATCH)는 비교 기준을 받은 뒤 붙인다.
 */
public enum CheckCode {

    // Mock ↔ be — 무조건

    /** 결제 가능 · 결제 완료인데 Mock 에 없음 */
    REGISTERED_NOT_IN_MOCK,
    /** 결제 가능 · 결제 완료인데 Mock 은 취소 */
    REGISTERED_BUT_MOCK_CANCELED,
    /** be 외부 번호 ≠ Mock 번호 */
    NUMBER_MISMATCH,
    /** 회원 · 상품 · sku 가 다름 */
    CONTENT_MISMATCH,
    /** 취소 완료인데 Mock 은 활성 */
    CANCELED_BUT_MOCK_ACTIVE,
    /** Mock 에 활성인데 be 에 그 키의 예약이 없음 */
    MOCK_ONLY,

    // Mock ↔ be — 좋음

    /** 등록 대기 · 등록 작업 DEAD_LETTER 인데 Mock 은 활성 — 응답을 잃은 등록 */
    PENDING_BUT_MOCK_ACTIVE,
    /** 등록 대기인데 Mock 에 취소 표식 — 같은 키로 다시 등록할 수 없다 */
    PENDING_BUT_MOCK_CANCEL_MARKER,
    /** 취소 완료인데 Mock 에 그 키의 행이 없음 — 늦은 등록을 막을 표식이 없다 */
    CANCELED_WITHOUT_MOCK_MARKER,

    // be 내부

    /** 예약 상태와 등록 작업 상태가 맞지 않음 */
    JOB_STATE_MISMATCH,
    /** 등록 작업이 성공인데 외부 번호가 없음 */
    NUMBER_MISSING,
    /** 외부 번호가 있는데 등록 작업이 성공이 아님 */
    NUMBER_WITHOUT_SUCCESS,
    /** 취소 완료인데 취소 작업이 없거나 성공이 아님 */
    CANCEL_JOB_INCOMPLETE,
    /** 기준 시간을 넘긴 등록 대기 · 취소 중 예약, 미발행 이벤트, 결과 없는 시도 */
    STUCK,
    /** 등록 작업이 없거나, 대기 중인 등록 작업의 발행 이벤트가 없음 */
    OUTBOX_MISSING,
    /** 예약 상태 ≠ 마지막 이력의 상태 */
    HISTORY_MISMATCH,
    /** 순번이 배정된 배송 차수 구간 밖 */
    SHIPMENT_BATCH_MISMATCH,
    /** 상품의 최대 순번 ≠ 예약 수 */
    QUEUE_GAP
}
