package com.grandis.nova.member.auth;

import com.grandis.nova.common.ErrorCode;

/** member 인증의 도메인 오류. api-spec F-X-01 의 코드 이름 그대로. 공통 코드(401 UNAUTHENTICATED 등)는 CommonErrorCode. */
public enum AuthErrorCode implements ErrorCode {
    /** 카카오 code 교환·사용자 조회가 안 됐다(만료·재사용된 code, 허용되지 않은 redirect_uri, 카카오 장애). api-spec: 400 */
    INVALID_OAUTH_CALLBACK(400, "인증 요청을 확인할 수 없습니다."),
    /** 관리자 로그인 실패. api-spec: 401 */
    INVALID_CREDENTIALS(401, "로그인 정보를 확인해 주세요.");

    private final int status;
    private final String message;

    AuthErrorCode(int status, String message) {
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
