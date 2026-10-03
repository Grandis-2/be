package com.grandis.nova.preorder.preorder;

import java.util.Optional;

/**
 * 예약 자신의 생애주기이자 상태 머신. 결제 여부는 주문 · payments 가 주인이라 여기에 없다 —
 * 결제가 끝나도 PAYABLE 이다. 등록 실패 상태도 없다. 외부 등록이 끝내 실패하면 작업이 DEAD_LETTER 가 되고
 * 관리자가 재처리한다.
 *
 * <pre>
 * PENDING_SYNC ──REGISTER_CONFIRMED──▶ PAYABLE
 *      │                                  │
 *      └──────CANCEL_REQUESTED──┐  ┌──CANCEL_REQUESTED
 *                               ▼  ▼
 *                             CANCELING ──CANCEL_REJECTED──▶ PAYABLE
 *                                 │
 *                          CANCEL_COMPLETED
 *                                 ▼
 *                              CANCELED
 * </pre>
 */
public enum PreorderStatus {

    /** 접수됨. 외부 등록 대기. */
    PENDING_SYNC,
    /** 외부 등록 확인. payable_from 부터 24시간 결제 가능. */
    PAYABLE,
    /** 취소 처리 중. 활성으로 남아 재신청을 막는다. */
    CANCELING,
    /** 취소 완료. active_marker 가 NULL 이 되어 재신청할 수 있다. */
    CANCELED;

    /**
     * 이 상태에서 그 사건이 일어났을 때의 다음 상태. 이 상태에서 의미 없는 사건이면 비어 있다 —
     * 같은 메시지를 두 번 받았거나(이미 반영), 늦게 도착한 사건(취소 중인 예약의 등록 확인)이다.
     *
     * 상태 · 사건 조합을 모두 적는다. default 로 뭉뚱그리지 않아서 상태나 사건이 늘면 컴파일러가 빠진 곳을 알린다.
     */
    public Optional<PreorderStatus> next(PreorderTrigger trigger) {
        return switch (this) {
            case PENDING_SYNC -> switch (trigger) {
                case REGISTER_CONFIRMED -> Optional.of(PAYABLE);
                case CANCEL_REQUESTED -> Optional.of(CANCELING);
                case CANCEL_REJECTED, CANCEL_COMPLETED -> Optional.empty();
            };
            case PAYABLE -> switch (trigger) {
                case CANCEL_REQUESTED -> Optional.of(CANCELING);
                case REGISTER_CONFIRMED, CANCEL_REJECTED, CANCEL_COMPLETED -> Optional.empty();
            };
            case CANCELING -> switch (trigger) {
                case CANCEL_COMPLETED -> Optional.of(CANCELED);
                case CANCEL_REJECTED -> Optional.of(PAYABLE);
                case REGISTER_CONFIRMED, CANCEL_REQUESTED -> Optional.empty();
            };
            case CANCELED -> Optional.empty();
        };
    }
}
