package com.grandis.nova.common.security;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;

/**
 * 토큰이 형식·서명·issuer·만료·필수 클레임 중 하나라도 어긋난다.
 * api-spec 공통 오류의 401 UNAUTHENTICATED("세션 없음·만료")에 그대로 대응한다.
 * 어느 검사에서 걸렸는지는 로그용 reason 에만 남기고 응답 문구에는 내지 않는다 — 공격자에게 힌트가 된다.
 * BusinessException 이 cause 와 스택을 비운 채 만들어지므로(예상된 실패) cause 는 잇지 않는다. 필터가 reason 을 WARN 으로 남긴다.
 */
public class InvalidTokenException extends BusinessException {

    private final String reason;

    public InvalidTokenException(String reason) {
        super(CommonErrorCode.UNAUTHENTICATED);
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
