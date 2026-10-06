package com.grandis.nova.catalog;

import com.grandis.nova.common.ErrorCode;

/**
 * catalog 가 던지는 업무 오류. 이름 · 상태 · 문구는 계약(contracts/*.md)과 같다. 쓰는 곳이 생길 때 추가한다.
 *
 * PRODUCT_NOT_FOUND 는 내부 API(contracts/preorder-internal.md)용이다. 틀린 경로의 404 는 공통 NOT_FOUND 로 나가므로
 * 호출자가 "없는 상품" 과 "어긋난 배포 경로" 를 코드로 가른다. 공개 API 는 명세대로 NOT_FOUND 를 쓴다 — 이 값을 그대로 쓰지 않는다.
 */
public enum CatalogErrorCode implements ErrorCode {

    PRODUCT_NOT_FOUND(404, "상품을 찾을 수 없습니다."),
    REGISTRATION_NOT_FOUND(404, "등록 기록을 찾을 수 없습니다."),
    REGISTRATION_IN_PROGRESS(409, "같은 등록이 처리 중입니다. 잠시 후 같은 키로 다시 시도해 주세요."),
    /**
     * 지금 상태에서 못 하는 수정. 사전예약 오픈 3분 전부터의 수정(공개 여부 말고 전부 — 옵션 · 상품 판매 상태 포함), 회차가 취소된 상품의 수정,
     * 오픈 뒤 · 회차 취소 뒤의 판매 재개, DB 잠금 실패(교착 등)로 처리하지 못한 수정(details.retryable). 공개 여부 전환은 오픈 판정으로는
     * 이 코드가 나지 않는다(잠금 실패의 retryable 409 는 난다).
     */
    STATE_CONFLICT(409, "현재 상태에서는 처리할 수 없습니다. 최신 상태를 조회해 주세요."),
    /** 리뷰를 쓸 수 없는 주문상품 — 배송 완료 전이거나 사전예약 주문 · 상품이다. 문구가 이유를 말한다. */
    REVIEW_NOT_ALLOWED(409, "이 주문상품에는 리뷰를 쓸 수 없습니다."),
    /** 그 주문상품에 이미 리뷰가 있다(주문상품 1건당 1개). 고치려면 수정 API 를 쓴다. */
    REVIEW_ALREADY_WRITTEN(409, "이미 리뷰를 쓴 주문상품입니다.");

    private final int status;
    private final String message;

    CatalogErrorCode(int status, String message) {
        this.status = status;
        this.message = message;
    }

    @Override
    public int status() {
        return status;
    }

    @Override
    public String defaultMessage() {
        return message;
    }
}
