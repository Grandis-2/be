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
    /** 사전예약 오픈 뒤의 상품 정보 · 옵션 · 가격 수정(설계 §2.1). 공개 전환 · 옵션 판매 중지는 이 코드가 아니다. */
    STATE_CONFLICT(409, "현재 상태에서는 처리할 수 없습니다. 최신 상태를 조회해 주세요.");

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
