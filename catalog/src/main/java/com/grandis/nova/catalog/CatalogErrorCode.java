package com.grandis.nova.catalog;

import com.grandis.nova.common.ErrorCode;

/**
 * catalog 가 던지는 업무 오류. 이름 · 상태 · 문구는 계약(contracts/*.md)과 같다. 쓰는 곳이 생길 때 추가한다.
 *
 * PRODUCT_NOT_FOUND 는 내부 API(contracts/preorder-internal.md)용이다. 틀린 경로의 404 는 공통 NOT_FOUND 로 나가므로
 * 호출자가 "없는 상품" 과 "어긋난 배포 경로" 를 코드로 가른다. 공개 API 는 명세대로 NOT_FOUND 를 쓴다 — 이 값을 그대로 쓰지 않는다.
 */
public enum CatalogErrorCode implements ErrorCode {

    PRODUCT_NOT_FOUND(404, "상품을 찾을 수 없습니다.");

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
