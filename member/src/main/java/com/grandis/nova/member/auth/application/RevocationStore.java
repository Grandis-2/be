package com.grandis.nova.member.auth.application;

import java.time.Duration;
import java.util.UUID;

/**
 * 쓰기 쪽 폐기 표식. 읽기는 common:security 의 RevocationChecker 가 한다. 키는 AuthRedisKeys 하나를 같이 본다.
 */
public interface RevocationStore {

    /** 세션 하나를 끊는다(로그아웃·리프레시 재사용 탐지). TTL 은 액세스 만료 — 그 뒤엔 토큰이 어차피 죽는다. */
    void revokeSession(UUID sessionId, Duration accessTokenTtl);

    /**
     * 회원의 모든 세션을 끊는다(제재·탈퇴). sid 를 열거하지 않고 "지금 이전에 발급된 토큰은 전부 무효" 를 심는다.
     * 같은 초에 발급된 토큰도 무효다(RevocationRedisChecker 의 iat ≤ nbf). TTL 은 리프레시 만료 — 그 뒤엔 남은 토큰이 없다.
     */
    void revokeAll(String subject, Duration refreshTokenTtl);
}
