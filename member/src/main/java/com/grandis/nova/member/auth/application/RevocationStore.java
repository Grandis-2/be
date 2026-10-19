package com.grandis.nova.member.auth.application;

import java.time.Duration;
import java.util.UUID;

/**
 * 쓰기 쪽 폐기 표식. 읽기는 common:security 의 RevocationChecker 가 한다. 키는 AuthRedisKeys 하나를 같이 본다.
 */
public interface RevocationStore {

    /**
     * 세션 하나를 끊는다. TTL 은 부르는 쪽이 정한다 — 로그아웃은 액세스 만료(그 뒤엔 토큰이 어차피 죽는다), 리프레시 재사용 탐지는 리프레시
     * 만료(체인 폐기가 실패해도 재발급을 막는다 — TokenService).
     * 이미 표식이 있으면 덮지 않는다 — 새 로그인에 밀린 세션의 표식(이유 · 리프레시 만료 TTL)을 그 기기의 로그아웃이 지우지 않게. 그래서 짧은 로그아웃
     * 표식이 먼저 있으면 재사용 표식으로 늘어나지 않는다. 이 순서는 생긴다 — 재사용 요청이 회전 전 표식 검사를 지난 뒤 같은 세션의 로그아웃이 표식을 먼저
     * 쓰면, 그 요청은 REUSED 로 판정되지만 표식은 로그아웃의 액세스 TTL 그대로다(리뷰 실측 10/10). 그때 체인은 로그아웃과 재사용 양쪽의 체인 폐기로
     * 끊긴다 — 둘 다 실패해야 표식이 끝난 뒤(30분) 재발급이 다시 열린다.
     */
    void revokeSession(UUID sessionId, Duration ttl);

    /**
     * 같은 회원의 새 로그인에 밀린 세션을 끊는다. 표식 값이 달라 401 에 이유(SESSION_REPLACED)가 실린다.
     * TTL 은 리프레시 만료 — 끊긴 기기가 며칠 뒤 리프레시 쿠키로 와도 재발급 401 에 이유가 실린다.
     */
    void replaceSession(UUID sessionId, Duration refreshTokenTtl);

    /**
     * 회원의 모든 세션을 끊는다(제재·탈퇴). sid 를 열거하지 않고 "지금 이전에 발급된 토큰은 전부 무효" 를 심는다.
     * 같은 초에 발급된 토큰도 무효다(RevocationRedisChecker 의 iat ≤ nbf). TTL 은 리프레시 만료 — 그 뒤엔 남은 토큰이 없다.
     */
    void revokeAll(String subject, Duration refreshTokenTtl);
}
