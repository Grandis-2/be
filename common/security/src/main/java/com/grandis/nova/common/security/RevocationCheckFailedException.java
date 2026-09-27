package com.grandis.nova.common.security;

/**
 * 폐기 표식 조회가 되지 않았다. 폐기 여부를 모른다는 뜻이지 폐기됐다는 뜻이 아니다.
 * 필터가 경로별 정책으로 처리한다: fail-closed 경로면 401, 아니면 경고·카운터를 남기고 통과.
 * 원인 예외의 이름만 남긴다. 스택은 필터가 WARN 로그에 남길지 정한다.
 */
public class RevocationCheckFailedException extends RuntimeException {

    public RevocationCheckFailedException(Throwable cause) {
        super("revocation lookup failed (" + cause.getClass().getSimpleName() + ")", cause);
    }
}
