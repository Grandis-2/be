package com.grandis.nova.common.security;

import java.util.UUID;

/**
 * 인증이 쓰는 Redis 키의 단일 출처. 쓰는 쪽(member)과 읽는 쪽(필터, HTTP 서비스 4개)이 같은 상수를 본다.
 * 대기열과 Redis 한 대를 같이 쓰므로 접두 `auth:` 로 가른다. 클러스터 모드는 SELECT 로 논리 DB 를 못 나눠 접두가 유일한 경계다.
 * 인증이 쓰는 키는 이 여섯뿐이다.
 */
public final class AuthRedisKeys {

    /** 관리자 리프레시의 지금 jti. TTL = 리프레시 만료. 발급·회전·로그아웃이 쓴다. */
    public static final String REFRESH_PREFIX = "auth:refresh:";

    /** 관리자 리프레시 회전 유예 — `바로앞jti|다음jti|유예끝(epoch ms)`. TTL = 유예 시간. 회전이 쓰고 로그아웃이 지운다. */
    public static final String REFRESH_GRACE_PREFIX = "auth:refresh-grace:";

    /**
     * 세션(sid) 폐기 표식. 값은 {@link #REVOKED_SESSION_MARK}(로그아웃 TTL = 액세스 만료 · 리프레시 재사용 탐지 TTL = 리프레시 만료) 또는
     * {@link #REPLACED_SESSION_MARK}(같은 회원이 다른 곳에서 새로 로그인해 끊긴 세션, TTL = 리프레시 만료 — 재발급 401 에도 이유를 싣는다).
     * member 가 심고 필터가 읽는다. 값과 상관없이 키가 있으면 폐기다.
     */
    public static final String REVOKED_SESSION_PREFIX = "auth:revoked-sid:";

    /** 로그아웃 · 재사용 탐지로 끊긴 세션의 표식 값. */
    public static final String REVOKED_SESSION_MARK = "1";

    /** 같은 회원의 새 로그인에 밀려 끊긴 세션의 표식 값. 이 값이면 401 이 {@code details.reason = SESSION_REPLACED} 다. */
    public static final String REPLACED_SESSION_MARK = "replaced";

    /** 회원 단위 not-before(epoch 초). 이 시각 이전에 발급된 토큰은 전부 무효. TTL = 리프레시 만료. 제재·탈퇴가 심는다. */
    public static final String NOT_BEFORE_PREFIX = "auth:nbf:";

    /** 관리자 로그인 시도 수(클라이언트 IP 별). TTL = 제한 창(기본 1분). member 의 관리자 로그인만 쓰고 읽는다. 성공하면 지운다. */
    public static final String ADMIN_LOGIN_ATTEMPTS_PREFIX = "auth:admin-login:";

    /** 관리자 로그인 시도 수(모든 IP 합계, IP 별 제한을 통과한 것만). TTL = 제한 창. 성공하면 지운다. 키 하나다(접두가 아니다). */
    public static final String ADMIN_LOGIN_ATTEMPTS_TOTAL = "auth:admin-login-total";

    private AuthRedisKeys() {
    }

    public static String refresh(UUID sessionId) {
        return REFRESH_PREFIX + sessionId;
    }

    public static String refreshGrace(UUID sessionId) {
        return REFRESH_GRACE_PREFIX + sessionId;
    }

    public static String revokedSession(UUID sessionId) {
        return REVOKED_SESSION_PREFIX + sessionId;
    }

    public static String notBefore(String subject) {
        return NOT_BEFORE_PREFIX + subject;
    }

    public static String adminLoginAttempts(String clientIp) {
        return ADMIN_LOGIN_ATTEMPTS_PREFIX + clientIp;
    }
}
