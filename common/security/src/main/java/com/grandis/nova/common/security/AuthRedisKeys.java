package com.grandis.nova.common.security;

import java.util.UUID;

/**
 * 인증이 쓰는 Redis 키의 단일 출처. 쓰는 쪽(member)과 읽는 쪽(필터, HTTP 서비스 4개)이 같은 상수를 본다.
 * 대기열과 Redis 한 대를 같이 쓰므로 접두 `auth:` 로 가른다. 클러스터 모드는 SELECT 로 논리 DB 를 못 나눠 접두가 유일한 경계다.
 * 인증이 쓰는 키는 이 셋뿐이다.
 */
public final class AuthRedisKeys {

    /** 현재 리프레시 jti. TTL = 리프레시 만료. 발급·회전·로그아웃이 쓴다. */
    public static final String REFRESH_PREFIX = "auth:refresh:";

    /** 세션(sid) 폐기 표식. TTL = 액세스 만료. 로그아웃·재사용 탐지가 심고 필터가 읽는다. */
    public static final String REVOKED_SESSION_PREFIX = "auth:revoked-sid:";

    /** 회원 단위 not-before(epoch 초). 이 시각 이전에 발급된 토큰은 전부 무효. TTL = 리프레시 만료. 제재·탈퇴가 심는다. */
    public static final String NOT_BEFORE_PREFIX = "auth:nbf:";

    private AuthRedisKeys() {
    }

    public static String refresh(UUID sessionId) {
        return REFRESH_PREFIX + sessionId;
    }

    public static String revokedSession(UUID sessionId) {
        return REVOKED_SESSION_PREFIX + sessionId;
    }

    public static String notBefore(String subject) {
        return NOT_BEFORE_PREFIX + subject;
    }
}
